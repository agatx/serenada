/// Tests for SignalingClient.forcePingWithDeadline (resilience #8).
///
/// Verifies that the foreground force-ping path sends a synthetic ping,
/// closes the transport when no pong arrives within the deadline, and
/// stays no-op when not connected.

import XCTest
@testable import SerenadaCore

@MainActor
final class SignalingClientForcePingTests: XCTestCase {

    private var transports: [FakeSignalingTransport] = []
    private var client: SignalingClient!
    private var listener: RecordingListener!
    private var fakeClock: FakeSessionClock!

    override func setUp() {
        transports = []
        listener = RecordingListener()
        fakeClock = FakeSessionClock()
        client = SignalingClient(clock: fakeClock, transportFactory: { [self] kind in
            let t = FakeSignalingTransport(kind: kind)
            self.transports.append(t)
            return t
        })
        client.listener = listener
    }

    override func tearDown() {
        client.close()
        client = nil
        listener = nil
        transports = []
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
    }

    private var wsTransport: FakeSignalingTransport? {
        transports.first { $0.kind == .ws }
    }

    private func connectAndOpen() async {
        client.connect(host: "example.com")
        await settle()
        wsTransport?.simulateOpen()
        await settle()
        XCTAssertTrue(client.isConnected())
        wsTransport?.clearSentMessages()
    }

    func testForcePingSendsSyntheticPingImmediately() async {
        await connectAndOpen()

        client.forcePingWithDeadline(timeoutMs: 2_000)
        await settle()

        let pings = wsTransport?.sentMessages.filter { $0.type == "ping" } ?? []
        XCTAssertEqual(pings.count, 1)
    }

    func testForcePingClosesTransportWhenPongDoesNotArrive() async {
        await connectAndOpen()

        client.forcePingWithDeadline(timeoutMs: 2_000)
        await settle()

        // Advance past deadline without a pong.
        await fakeClock.advance(byMs: 2_500)
        await settle()

        XCTAssertFalse(client.isConnected())
        XCTAssertEqual(listener.closeReasons, ["foreground_force_ping_timeout"])
    }

    func testForcePingDoesNotCloseWhenPongArrives() async {
        await connectAndOpen()

        client.forcePingWithDeadline(timeoutMs: 2_000)
        await settle()

        // Pong arrives well before deadline.
        client.recordPong()

        // Advance past deadline.
        await fakeClock.advance(byMs: 2_500)
        await settle()

        XCTAssertTrue(client.isConnected())
        XCTAssertTrue(listener.closeReasons.isEmpty)
    }

    func testForcePingIsNoOpWhenNotConnected() async {
        // Never connected.
        client.forcePingWithDeadline(timeoutMs: 2_000)
        await fakeClock.advance(byMs: 2_500)
        await settle()

        let ws = transports.first { $0.kind == .ws }
        XCTAssertEqual(ws?.sentMessages.count ?? 0, 0)
        XCTAssertTrue(listener.closeReasons.isEmpty)
    }

    func testForcePingCalledTwiceCancelsEarlierDeadline() async {
        await connectAndOpen()

        client.forcePingWithDeadline(timeoutMs: 5_000)
        await settle()
        // Advance partway, then issue a fresh force-ping with a new deadline.
        await fakeClock.advance(byMs: 1_000)
        await settle()

        client.forcePingWithDeadline(timeoutMs: 2_000)
        await settle()
        // Pong arrives before the second deadline (and well before the first).
        client.recordPong()
        await fakeClock.advance(byMs: 2_500)
        await settle()

        XCTAssertTrue(client.isConnected())
        XCTAssertTrue(listener.closeReasons.isEmpty)
    }

    func testForcePingAfterCloseIsNoOp() async {
        await connectAndOpen()
        client.close()
        await settle()
        wsTransport?.clearSentMessages()

        client.forcePingWithDeadline(timeoutMs: 2_000)
        await fakeClock.advance(byMs: 2_500)
        await settle()

        XCTAssertEqual(wsTransport?.sentMessages.count ?? 0, 0)
    }
}

@MainActor
private final class RecordingListener: SignalingClientListener {
    var openTransports: [String] = []
    var messages: [SignalingMessage] = []
    var closeReasons: [String] = []

    func onOpen(activeTransport: String) {
        openTransports.append(activeTransport)
    }

    func onMessage(_ message: SignalingMessage) {
        messages.append(message)
    }

    func onClosed(reason: String) {
        closeReasons.append(reason)
    }
}
