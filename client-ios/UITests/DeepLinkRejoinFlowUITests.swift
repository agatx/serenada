import XCTest

@MainActor
final class DeepLinkRejoinFlowUITests: XCTestCase {
    private let serverHost = "https://serenada.app"

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testJoinLeaveAndRejoinFromRecentsTwice() async throws {
        let deepLinkURL = try await resolveDeepLinkURL()
        let roomId = try extractRoomId(from: deepLinkURL)

        let app = XCUIApplication()
        app.launchArguments += [
            "-ui-testing",
            "-ui-testing-clear-state"
        ]
        app.launchEnvironment["SERENADA_UI_TEST_DEEPLINK"] = deepLinkURL

        addSystemAlertHandler()
        app.launch()
        app.activate()

        guard assertCallScreenVisible(in: app, timeout: 45) else { return }
        guard waitForPeerToJoin(in: app, timeout: 25) else { return }

        guard leaveCall(in: app) else { return }
        guard assertJoinScreenVisible(in: app, timeout: 20) else { return }

        guard rejoinFromRecents(in: app, roomId: roomId, timeout: 20) else { return }
        guard assertCallScreenVisible(in: app, timeout: 45) else { return }
        guard waitForPeerToJoin(in: app, timeout: 25) else { return }

        guard leaveCall(in: app) else { return }
        guard assertJoinScreenVisible(in: app, timeout: 20) else { return }

        guard rejoinFromRecents(in: app, roomId: roomId, timeout: 20) else { return }
        guard assertCallScreenVisible(in: app, timeout: 45) else { return }
        _ = waitForPeerToJoin(in: app, timeout: 25)
    }

    private func resolveDeepLinkURL() async throws -> String {
        if let override = resolveDeepLinkOverride() {
            return override
        }
        let roomId = try await createRoomId()
        return "\(serverHost)/call/\(roomId)"
    }

    private func resolveDeepLinkOverride() -> String? {
        let environment = ProcessInfo.processInfo.environment
        let keys = [
            "SERENADA_UI_TEST_REJOIN_DEEPLINK",
            "TEST_RUNNER_SERENADA_UI_TEST_REJOIN_DEEPLINK"
        ]

        for key in keys {
            let value = environment[key]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !value.isEmpty {
                return value
            }
        }

        return nil
    }

    private func extractRoomId(from deepLinkURL: String) throws -> String {
        guard let url = URL(string: deepLinkURL), !url.pathComponents.isEmpty else {
            XCTFail("Invalid deep link URL: \(deepLinkURL)")
            throw URLError(.badURL)
        }
        let roomId = url.pathComponents.last?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if roomId.isEmpty || roomId == "/" {
            XCTFail("Deep link URL missing room ID: \(deepLinkURL)")
            throw URLError(.cannotParseResponse)
        }
        return roomId
    }

    private func addSystemAlertHandler() {
        addUIInterruptionMonitor(withDescription: "System Alerts") { alert in
            let preferredButtons = [
                "Allow",
                "Allow While Using App",
                "Allow Once",
                "OK",
                "Continue"
            ]

            for label in preferredButtons where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }

            if alert.buttons.count > 0 {
                alert.buttons.element(boundBy: 0).tap()
                return true
            }

            return false
        }
    }

    @discardableResult
    private func assertCallScreenVisible(
        in app: XCUIApplication,
        timeout: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool {
        let callScreen = app.otherElements["call.screen"]
        if !waitForCallScreenOrDetectJoinStall(in: app, timeout: timeout) {
            let screenshotAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
            screenshotAttachment.name = "MissingCallScreen"
            screenshotAttachment.lifetime = .keepAlways
            add(screenshotAttachment)

            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "MissingCallScreenHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)

            let joinScreenExists = app.otherElements["join.screen"].exists
            let busyOverlayExists = app.otherElements["join.busyOverlay"].exists
            XCTFail(
                "Call screen did not appear within \(timeout)s. join.screen=\(joinScreenExists), join.busyOverlay=\(busyOverlayExists)",
                file: file,
                line: line
            )
            return false
        }

        guard revealCallControlsAndWaitForEndCall(in: app, timeout: 12) else {
            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "MissingEndCallHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)
            XCTFail("End call button is missing on call screen", file: file, line: line)
            return false
        }
        return true
    }

    private func waitForCallScreenOrDetectJoinStall(in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let callScreen = app.otherElements["call.screen"]
        let joinScreen = app.otherElements["join.screen"]
        let busyOverlay = app.otherElements["join.busyOverlay"]

        let deadline = Date().addingTimeInterval(timeout)
        var busySince: Date?

        while Date() < deadline {
            if callScreen.exists {
                return true
            }

            if joinScreen.exists && busyOverlay.exists {
                if busySince == nil {
                    busySince = Date()
                } else if Date().timeIntervalSince(busySince!) >= 12 {
                    return false
                }
            } else {
                busySince = nil
            }

            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }

        return callScreen.exists
    }

    @discardableResult
    private func assertJoinScreenVisible(
        in app: XCUIApplication,
        timeout: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool {
        let joinScreen = app.otherElements["join.screen"]
        guard joinScreen.waitForExistence(timeout: timeout) else {
            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "MissingJoinScreenHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)
            XCTFail("Join screen did not appear within \(timeout)s", file: file, line: line)
            return false
        }
        return true
    }

    @discardableResult
    private func leaveCall(
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool {
        let endCallButton = app.buttons["call.endCall"]
        guard revealCallControlsAndWaitForEndCall(in: app, timeout: 12) else {
            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "LeaveCallControlsNotHittableHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)
            XCTFail("End call control did not become hittable", file: file, line: line)
            return false
        }

        endCallButton.tap()
        return true
    }

    @discardableResult
    private func rejoinFromRecents(
        in app: XCUIApplication,
        roomId: String,
        timeout: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool {
        let recentCallButton = app.descendants(matching: .any)["join.recentCall.\(roomId)"]
        guard recentCallButton.waitForExistence(timeout: timeout) else {
            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "MissingRecentCallHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)
            XCTFail("Recent call entry for room \(roomId) was not found", file: file, line: line)
            return false
        }

        if !recentCallButton.isHittable {
            app.swipeUp()
        }
        recentCallButton.tap()
        return true
    }

    private func revealCallControlsAndWaitForEndCall(in app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let endCallButton = app.buttons["call.endCall"]
        if endCallButton.isHittable {
            return true
        }

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            if endCallButton.waitForExistence(timeout: 1.0), endCallButton.isHittable {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }

        return endCallButton.exists && endCallButton.isHittable
    }

    @discardableResult
    private func waitForPeerToJoin(
        in app: XCUIApplication,
        timeout: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line
    ) -> Bool {
        let participantCountElement = app.otherElements["call.participantCount"]
        guard participantCountElement.waitForExistence(timeout: 3) else {
            let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
            hierarchyAttachment.name = "MissingParticipantCountHierarchy"
            hierarchyAttachment.lifetime = .keepAlways
            add(hierarchyAttachment)
            XCTFail("Participant count probe is missing", file: file, line: line)
            return false
        }

        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let count = currentParticipantCount(from: participantCountElement), count >= 2 {
                return true
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }

        let hierarchyAttachment = XCTAttachment(string: app.debugDescription)
        hierarchyAttachment.name = "PeerDidNotJoinHierarchy"
        hierarchyAttachment.lifetime = .keepAlways
        add(hierarchyAttachment)
        let observedCount = currentParticipantCount(from: participantCountElement).map(String.init) ?? "unknown"
        XCTFail("Peer did not join call within \(timeout)s (participantCount=\(observedCount))", file: file, line: line)
        return false
    }

    private func currentParticipantCount(from element: XCUIElement) -> Int? {
        if let number = element.value as? NSNumber {
            return number.intValue
        }
        if let value = element.value as? String {
            return Int(value.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private func createRoomId() async throws -> String {
        guard let url = URL(string: "\(serverHost)/api/room-id") else {
            XCTFail("Invalid room-id endpoint URL")
            throw URLError(.badURL)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = Data()
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse, (200...299).contains(httpResponse.statusCode) else {
            XCTFail("Failed to create room ID from server")
            throw URLError(.badServerResponse)
        }

        struct RoomIdResponse: Decodable {
            let roomId: String
        }

        let decoded = try JSONDecoder().decode(RoomIdResponse.self, from: data)
        let roomId = decoded.roomId.trimmingCharacters(in: .whitespacesAndNewlines)
        if roomId.isEmpty {
            XCTFail("Server returned empty room ID")
            throw URLError(.cannotParseResponse)
        }
        return roomId
    }
}
