/// Transport fallback tests for SignalingClient.
///
/// Mirrors the web SDK's SignalingEngine fallback tests. Uses FakeSignalingTransport
/// injected via the transportFactory parameter to verify WS→SSE fallback logic.

import XCTest
@testable import SerenadaCore

@MainActor
final class SignalingClientFallbackTests: XCTestCase {

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

    private var sseTransport: FakeSignalingTransport? {
        transports.first { $0.kind == .sse }
    }

    // MARK: - Tests

    func testFallsBackToSseWhenWsNeverConnected() async {
        client.connect(host: "example.com")
        await settle()

        let ws = wsTransport
        XCTAssertNotNil(ws, "Should create WS transport first")
        XCTAssertEqual(ws?.connectCalls, 1)

        // WS fails without ever opening.
        ws?.simulateClose("error")
        await settle()

        // Engine should fall back to SSE.
        let sse = sseTransport
        XCTAssertNotNil(sse, "Should create SSE transport as fallback")
        XCTAssertEqual(sse?.connectCalls, 1)
    }

    func testFallsBackToSseWhenWsDropsWithTimeout() async {
        client.connect(host: "example.com")
        await settle()

        let ws = wsTransport!
        ws.simulateOpen()
        await settle()

        XCTAssertTrue(client.isConnected())
        XCTAssertEqual(listener.openTransports, ["ws"])

        // WS drops with timeout reason.
        ws.simulateClose("timeout")
        await settle()

        let sse = sseTransport
        XCTAssertNotNil(sse, "Should fall back to SSE after timeout")
        XCTAssertEqual(sse?.connectCalls, 1)
    }

    func testFallsBackToSseWhenWsUnsupported() async {
        client.connect(host: "example.com")
        await settle()

        let ws = wsTransport!

        // WS reports unsupported without opening.
        ws.simulateClose("unsupported")
        await settle()

        let sse = sseTransport
        XCTAssertNotNil(sse, "Should fall back to SSE when WS unsupported")
        XCTAssertEqual(sse?.connectCalls, 1)
    }

    func testSseConnectsSuccessfullyAfterWsFallback() async {
        client.connect(host: "example.com")
        await settle()

        wsTransport?.simulateClose("error")
        await settle()

        sseTransport?.simulateOpen()
        await settle()

        XCTAssertTrue(client.isConnected())
        XCTAssertEqual(listener.openTransports.last, "sse")
    }

    func testNoFallbackWithSingleTransport() async {
        // Re-create client with SSE only.
        client.close()
        transports.removeAll()
        let newListener = RecordingListener()
        listener = newListener
        client = SignalingClient(forceSseSignaling: true, clock: fakeClock, transportFactory: { [self] kind in
            let t = FakeSignalingTransport(kind: kind)
            self.transports.append(t)
            return t
        })
        client.listener = newListener

        client.connect(host: "example.com")
        await settle()

        let sse = sseTransport!
        sse.simulateClose("error")
        await settle()

        // With single transport, no fallback — should notify listener of closure.
        XCTAssertEqual(newListener.closeReasons.count, 1)
        XCTAssertEqual(newListener.closeReasons.first, "error")
    }

    func testMessagesRoutedThroughActiveTransport() async {
        client.connect(host: "example.com")
        await settle()

        wsTransport?.simulateOpen()
        await settle()

        let msg = SignalingMessage(type: "join", rid: "room-1")
        client.send(msg)

        XCTAssertEqual(wsTransport?.sentMessages.count, 1)
        XCTAssertEqual(wsTransport?.sentMessages.first?.type, "join")
    }

    func testForceClosesAfterMissedPongThreshold() async {
        client.connect(host: "example.com")
        await settle()

        wsTransport?.simulateOpen()
        await settle()

        XCTAssertTrue(client.isConnected())

        // Advance past PING_INTERVAL (12s) — first tick sends ping.
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()

        let pings = wsTransport?.sentMessages.filter { $0.type == "ping" } ?? []
        XCTAssertGreaterThanOrEqual(pings.count, 1, "Should have sent at least one ping")

        // Advance two more intervals without pong → missedPongs reaches threshold.
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()

        XCTAssertFalse(client.isConnected(), "Should disconnect after missed pongs")
    }

    func testPongResetsMissedPongCounter() async {
        client.connect(host: "example.com")
        await settle()

        wsTransport?.simulateOpen()
        await settle()

        // First tick — sends ping.
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()

        // Respond with pong.
        client.recordPong()

        // Two more ticks — since pong was received, counter resets.
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()
        await fakeClock.advance(byMs: Int64(WebRtcResilience.pingIntervalMs))
        await settle()

        XCTAssertTrue(client.isConnected(), "Should still be connected after pong reset")
    }
}

// MARK: - Recording Listener

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
