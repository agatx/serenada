@testable import SerenadaCore
import XCTest
import WebRTC

/// Regression coverage for the "mobile drops remote video" bug (commit
/// b82088d0): `PeerConnectionSlot.routeRemoteVideoTrack` classified the inbound
/// remote video transceiver by OBJECT IDENTITY (`transceiver === cameraTransceiver`
/// / `=== contentTransceiver`). Native libwebrtc hands the onTrack /
/// didStartReceivingOn callback a DIFFERENT `RTCRtpTransceiver` wrapper than the
/// one cached when roles were bound (same underlying native transceiver / same
/// negotiated `mid`), so both `===` checks failed and the remote CAMERA track
/// was dropped to the "Ignoring unbound remote video track" branch — no remote
/// video. The fix classifies by `transceiver.mid` first (stable across wrappers).
///
/// Why prior tests missed it: the role-binding/receive path was only exercised
/// through `FakePeerConnectionSlot`, which reuses the SAME object identity, so an
/// identity-vs-mid bug is structurally invisible there. This test drives the REAL
/// `PeerConnectionSlot.routeRemoteVideoTrack` and reproduces the wrapper churn
/// deterministically: `RTCPeerConnection.transceivers` returns a FRESH wrapper
/// object on each access (verified: `t1 === t2` is false) while the negotiated
/// `mid` is stable. We bind camera/content from one access, then deliver to the
/// receive path a wrapper from a SECOND access (distinct identity, identical mid)
/// and assert the track still routes to the correct role.
@MainActor
final class RemoteVideoTransceiverRoutingTests: XCTestCase {

    /// One real peer connection with two recvOnly video m-lines, negotiated far
    /// enough (offer + setLocalDescription) that mids "0" / "1" are assigned.
    /// Each property re-reads `pc.transceivers` so callers get FRESH wrappers.
    private struct NegotiatedPeer {
        let factory: RTCPeerConnectionFactory
        let pc: RTCPeerConnection
        /// Fresh camera-role wrapper (mid "0") — new object on every access.
        var cameraWrapper: RTCRtpTransceiver { pc.transceivers[0] }
        /// Fresh content-role wrapper (mid "1") — new object on every access.
        var contentWrapper: RTCRtpTransceiver { pc.transceivers[1] }

        func makeVideoTrack(_ id: String) -> RTCVideoTrack {
            factory.videoTrack(with: factory.videoSource(), trackId: id)
        }
    }

    private func makeNegotiatedPeer() throws -> NegotiatedPeer {
        let factory = RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        let pc = try XCTUnwrap(
            factory.peerConnection(
                with: config,
                constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil),
                delegate: nil
            ),
            "peer connection"
        )

        // First video m-line → camera role (mid "0"), second → content (mid "1").
        let camInit = RTCRtpTransceiverInit()
        camInit.direction = .recvOnly
        _ = pc.addTransceiver(of: .video, init: camInit)
        let contentInit = RTCRtpTransceiverInit()
        contentInit.direction = .recvOnly
        _ = pc.addTransceiver(of: .video, init: contentInit)

        // Assign negotiated mids by setting a local offer.
        let exp = expectation(description: "setLocalDescription")
        pc.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { sdp, err in
            guard let sdp, err == nil else { exp.fulfill(); return }
            pc.setLocalDescription(sdp) { _ in exp.fulfill() }
        }
        wait(for: [exp], timeout: 5)

        let peer = NegotiatedPeer(factory: factory, pc: pc)
        // Sanity: mids assigned as expected, and the two wrappers really do churn.
        XCTAssertEqual(peer.cameraWrapper.mid, "0", "camera role mid")
        XCTAssertEqual(peer.contentWrapper.mid, "1", "content role mid")
        XCTAssertFalse(
            peer.cameraWrapper === peer.cameraWrapper,
            "transceivers should return a FRESH wrapper per access (the bug's trigger)"
        )
        return peer
    }

    /// Build an independent-capable slot wired to capture the remote-camera
    /// callback, with the camera/content roles bound from one wrapper access.
    private func makeBoundSlot(
        _ peer: NegotiatedPeer,
        onRemoteCamera: @escaping (RTCVideoTrack?) -> Void
    ) -> PeerConnectionSlot {
        let slot = PeerConnectionSlot(
            remoteCid: "remote-cid-1",
            factory: peer.factory,
            iceServers: [],
            localAudioTrack: nil,
            localVideoTrack: nil,
            supportsIndependentContentVideo: true,
            onLocalIceCandidate: { _, _ in },
            onRemoteVideoTrack: { _, track in onRemoteCamera(track) },
            onConnectionStateChange: { _, _ in },
            onIceConnectionStateChange: { _, _ in },
            onSignalingStateChange: { _, _ in },
            onRenegotiationNeeded: { _ in }
        )
        // Bind roles to the wrappers from ONE access — exactly what the production
        // binding caches. Later receive wrappers will be DIFFERENT objects.
        slot._test_bindRoleTransceivers(
            peerConnection: peer.pc,
            camera: peer.cameraWrapper,
            content: peer.contentWrapper
        )
        return slot
    }

    // MARK: - The regression

    /// Inbound CAMERA track delivered on a fresh wrapper (distinct object, same
    /// mid "0" as the bound camera transceiver) must route to CAMERA. Before the
    /// fix the `===` check failed and this track was dropped (no remote video).
    func testRemoteCameraTrackOnFreshWrapperRoutesToCamera() throws {
        let peer = try makeNegotiatedPeer()
        var cameraCallbackTrack: RTCVideoTrack?
        var cameraCallbackFired = false
        let slot = makeBoundSlot(peer) { track in
            cameraCallbackFired = true
            cameraCallbackTrack = track
        }

        let inboundCamera = peer.makeVideoTrack("inbound-camera")
        // A DIFFERENT wrapper object than the bound camera transceiver, but the
        // SAME negotiated mid ("0") — the exact libwebrtc churn that broke `===`.
        let deliveredWrapper = peer.cameraWrapper
        XCTAssertEqual(deliveredWrapper.mid, "0")

        slot._test_routeRemoteVideoTrack(inboundCamera, transceiver: deliveredWrapper)

        XCTAssertTrue(cameraCallbackFired, "remote camera track must be routed to camera, not dropped")
        XCTAssertTrue(cameraCallbackTrack === inboundCamera, "the routed camera track is the inbound one")
        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "slot bound the inbound track as camera")
        XCTAssertNil(slot._test_remoteContentTrack, "camera track must not leak into the content role")
        slot.closePeerConnection()
    }

    /// Inbound CONTENT track delivered on a fresh wrapper (distinct object, same
    /// mid "1" as the bound content transceiver) must route to CONTENT.
    func testRemoteContentTrackOnFreshWrapperRoutesToContent() throws {
        let peer = try makeNegotiatedPeer()
        var cameraCallbackFired = false
        let slot = makeBoundSlot(peer) { _ in cameraCallbackFired = true }

        let inboundContent = peer.makeVideoTrack("inbound-content")
        let deliveredWrapper = peer.contentWrapper
        XCTAssertEqual(deliveredWrapper.mid, "1")

        slot._test_routeRemoteVideoTrack(inboundContent, transceiver: deliveredWrapper)

        XCTAssertTrue(slot._test_remoteContentTrack === inboundContent, "remote content track must be routed to content")
        XCTAssertNil(slot._test_remoteCameraTrack, "content track must not leak into the camera role")
        XCTAssertFalse(cameraCallbackFired, "content routing must not fire the remote-camera callback")
        slot.closePeerConnection()
    }

    /// iOS may deliver the only remote camera video callback through the legacy
    /// didAdd:stream path, which has no transceiver. If no camera has been bound
    /// yet, the nil-transceiver video must attach as a camera fallback instead of
    /// being dropped.
    func testNilTransceiverFirstDeliveryRoutesToCameraFallback() throws {
        let peer = try makeNegotiatedPeer()
        var cameraCallbackTrack: RTCVideoTrack?
        var cameraCallbackFired = false
        let slot = makeBoundSlot(peer) { track in
            cameraCallbackFired = true
            cameraCallbackTrack = track
        }

        let inboundCamera = peer.makeVideoTrack("legacy-stream-camera")
        slot._test_routeRemoteVideoTrack(inboundCamera, transceiver: nil)

        XCTAssertTrue(cameraCallbackFired, "nil-transceiver first delivery must surface a camera fallback")
        XCTAssertTrue(cameraCallbackTrack === inboundCamera, "fallback camera callback should carry the inbound track")
        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "slot should bind the fallback as remote camera")
        XCTAssertNil(slot._test_remoteContentTrack, "camera fallback must not bind content")
        slot.closePeerConnection()
    }

    /// A later transceiver-classified camera callback is authoritative and should
    /// replace the provisional nil-transceiver fallback.
    func testTransceiverCameraReplacesNilTransceiverFallback() throws {
        let peer = try makeNegotiatedPeer()
        var callbackTracks: [RTCVideoTrack?] = []
        let slot = makeBoundSlot(peer) { track in callbackTracks.append(track) }

        let fallback = peer.makeVideoTrack("legacy-stream-camera")
        slot._test_routeRemoteVideoTrack(fallback, transceiver: nil)
        XCTAssertTrue(slot._test_remoteCameraTrack === fallback, "precondition: fallback camera bound")

        let inboundCamera = peer.makeVideoTrack("inbound-camera")
        slot._test_routeRemoteVideoTrack(inboundCamera, transceiver: peer.cameraWrapper)

        XCTAssertEqual(callbackTracks.count, 2, "camera callback should fire for fallback and authoritative replacement")
        XCTAssertTrue(callbackTracks.last! === inboundCamera, "authoritative callback should carry the transceiver-classified camera")
        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "authoritative camera should replace fallback")
        XCTAssertNil(slot._test_remoteContentTrack, "camera replacement must not bind content")
        slot.closePeerConnection()
    }

    /// A legacy `didAdd:stream` delivery arrives with NO transceiver. For a
    /// capable peer this path cannot distinguish camera from content, and because
    /// native libwebrtc hands each callback a DISTINCT `RTCVideoTrack` wrapper for
    /// the same underlying track, the old "default to camera" behavior re-set
    /// (clobbered) the already-classified remote camera with a churned/content
    /// wrapper on every stream callback — the last one winning — which blanked
    /// remote video on iOS (Android's `onAddStream` is a no-op, so it never bit
    /// there). The fix ignores nil-transceiver deliveries only after a camera has
    /// already been classified; the transceiver-carrying receive paths are
    /// authoritative.
    func testNilTransceiverDeliveryDoesNotClobberBoundCamera() throws {
        let peer = try makeNegotiatedPeer()
        let slot = makeBoundSlot(peer) { _ in }

        // 1) Camera track classified normally via its transceiver (mid "0").
        let inboundCamera = peer.makeVideoTrack("inbound-camera")
        slot._test_routeRemoteVideoTrack(inboundCamera, transceiver: peer.cameraWrapper)
        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "camera routed before the legacy delivery")

        // 2) A DIFFERENT track arrives via the legacy stream callback (nil
        // transceiver) — the exact clobber trigger.
        let strayTrack = peer.makeVideoTrack("legacy-stream-track")
        slot._test_routeRemoteVideoTrack(strayTrack, transceiver: nil)

        // The classified camera must be untouched (not replaced by the stray track).
        XCTAssertTrue(
            slot._test_remoteCameraTrack === inboundCamera,
            "nil-transceiver delivery must NOT clobber the classified remote camera"
        )
        XCTAssertFalse(
            slot._test_remoteCameraTrack === strayTrack,
            "the stray legacy-stream track must not become the camera"
        )
        slot.closePeerConnection()
    }

    /// iOS can emit a legacy `didRemove:stream` callback with no transceiver
    /// after the Unified-Plan camera callback has already bound the valid remote
    /// camera. For capable peers that nil/no-transceiver removal is not
    /// role-aware and must not clear the classified camera track.
    func testNilTransceiverRemovalDoesNotClearBoundCamera() throws {
        let peer = try makeNegotiatedPeer()
        var callbackTracks: [RTCVideoTrack?] = []
        let slot = makeBoundSlot(peer) { track in callbackTracks.append(track) }

        let inboundCamera = peer.makeVideoTrack("inbound-camera")
        slot._test_routeRemoteVideoTrack(inboundCamera, transceiver: peer.cameraWrapper)
        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "precondition: camera routed before removal")

        slot._test_routeRemoteVideoTrack(nil, transceiver: nil)

        XCTAssertTrue(slot._test_remoteCameraTrack === inboundCamera, "nil-transceiver removal must not clear the camera")
        XCTAssertEqual(callbackTracks.count, 1, "ignoring legacy removal must not fire a nil camera callback")
        slot.closePeerConnection()
    }
}
