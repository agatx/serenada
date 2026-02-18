import XCTest
@testable import SerenadaiOS

final class JoinRecoveryTests: XCTestCase {
    func testResolveJoinRecoveryReturnsNilOutsideJoiningPhases() {
        XCTAssertNil(resolveJoinRecoveryState(currentPhase: .idle, participantHint: 1, preferInCall: false))
        XCTAssertNil(resolveJoinRecoveryState(currentPhase: .waiting, participantHint: 1, preferInCall: false))
        XCTAssertNil(resolveJoinRecoveryState(currentPhase: .inCall, participantHint: 2, preferInCall: true))
    }

    func testResolveJoinRecoveryDefaultsToWaitingWithSingleParticipant() {
        let recovered = resolveJoinRecoveryState(currentPhase: .joining, participantHint: nil, preferInCall: false)
        XCTAssertEqual(recovered, JoinRecoveryState(phase: .waiting, participantCount: 1))
    }

    func testResolveJoinRecoveryMovesToInCallWhenHintShowsPeer() {
        let recovered = resolveJoinRecoveryState(currentPhase: .joining, participantHint: 2, preferInCall: false)
        XCTAssertEqual(recovered, JoinRecoveryState(phase: .inCall, participantCount: 2))
    }

    func testResolveJoinRecoveryPrefersInCallForLivePeerTraffic() {
        let recovered = resolveJoinRecoveryState(currentPhase: .creatingRoom, participantHint: 1, preferInCall: true)
        XCTAssertEqual(recovered, JoinRecoveryState(phase: .inCall, participantCount: 2))
    }
}
