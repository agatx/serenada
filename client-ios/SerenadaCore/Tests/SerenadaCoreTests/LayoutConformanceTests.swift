import SerenadaCore
import XCTest

final class LayoutConformanceTests: XCTestCase {
    private static let strictTolerance: CGFloat = 0.005

    // MARK: - Parameterised harness

    private static let allFixtures: [FixtureCase] = (try? loadFixtures()) ?? []

    /// Index into `allFixtures` for the current invocation.
    private var fixtureIndex = -1

    override class var defaultTestSuite: XCTestSuite {
        let suite = XCTestSuite(name: NSStringFromClass(self))
        for i in allFixtures.indices {
            let test = LayoutConformanceTests(selector: #selector(verifyFixtureCase))
            test.fixtureIndex = i
            suite.addTest(test)
        }
        return suite
    }

    override var name: String {
        guard fixtureIndex >= 0, fixtureIndex < Self.allFixtures.count else {
            return super.name
        }
        return "-[LayoutConformanceTests \(Self.allFixtures[fixtureIndex].id)]"
    }

    // MARK: - Test body

    @objc private func verifyFixtureCase() {
        guard fixtureIndex >= 0, fixtureIndex < Self.allFixtures.count else {
            XCTFail("Invalid fixture index \(fixtureIndex)")
            return
        }

        let testCase = Self.allFixtures[fixtureIndex]
        let scene = testCase.scene
        let expected = testCase.expected
        let result = computeLayout(scene: scene)

        XCTAssertEqual(
            result.mode.rawValue,
            expected.mode,
            "\(testCase.id): mode mismatch"
        )

        XCTAssertEqual(
            result.tiles.count,
            expected.tileCount,
            "\(testCase.id): tile count mismatch"
        )

        for (index, expectedTile) in expected.tiles.enumerated() {
            guard index < result.tiles.count else {
                XCTFail("\(testCase.id): missing tile[\(index)]")
                continue
            }

            let actualTile = result.tiles[index]
            XCTAssertEqual(actualTile.id, expectedTile.id, "\(testCase.id) tile[\(index)] id")
            XCTAssertEqual(actualTile.type.rawValue, expectedTile.type, "\(testCase.id) tile[\(index)] type")
            XCTAssertEqual(actualTile.fit.rawValue, expectedTile.fit, "\(testCase.id) tile[\(index)] fit")

            let actualNorm = normalizeFrame(
                actualTile.frame,
                viewportWidth: scene.viewportWidth,
                viewportHeight: scene.viewportHeight
            )
            assertFrameClose(
                actualNorm,
                expectedTile.normalizedFrame,
                tolerance: Self.strictTolerance,
                label: "\(testCase.id) tile[\(index)]"
            )
        }

        if expected.localPip == nil {
            XCTAssertNil(result.localPip, "\(testCase.id): localPip should be nil")
        } else {
            XCTAssertNotNil(result.localPip, "\(testCase.id): localPip should not be nil")
            if let pip = result.localPip, let expectedPip = expected.localPip {
                XCTAssertEqual(pip.participantId, expectedPip.participantId, "\(testCase.id) pip participantId")
                XCTAssertEqual(anchorToString(pip.anchor), expectedPip.anchor, "\(testCase.id) pip anchor")

                let actualPipNorm = normalizeFrame(
                    pip.frame,
                    viewportWidth: scene.viewportWidth,
                    viewportHeight: scene.viewportHeight
                )
                assertFrameClose(
                    actualPipNorm,
                    expectedPip.normalizedFrame,
                    tolerance: Self.strictTolerance,
                    label: "\(testCase.id) pip"
                )
            }
        }
    }

    // MARK: - Helpers

    private struct NormalizedFrame {
        let x: CGFloat
        let y: CGFloat
        let width: CGFloat
        let height: CGFloat
    }

    private func normalizeFrame(_ frame: LayoutRect, viewportWidth: CGFloat, viewportHeight: CGFloat) -> NormalizedFrame {
        NormalizedFrame(
            x: frame.x / viewportWidth,
            y: frame.y / viewportHeight,
            width: frame.width / viewportWidth,
            height: frame.height / viewportHeight
        )
    }

    private func assertFrameClose(
        _ actual: NormalizedFrame,
        _ expected: FixtureNormalizedFrame,
        tolerance: CGFloat,
        label: String
    ) {
        XCTAssertLessThanOrEqual(abs(actual.x - expected.x), tolerance, "\(label) x: \(actual.x) vs \(expected.x)")
        XCTAssertLessThanOrEqual(abs(actual.y - expected.y), tolerance, "\(label) y: \(actual.y) vs \(expected.y)")
        XCTAssertLessThanOrEqual(abs(actual.width - expected.width), tolerance, "\(label) width: \(actual.width) vs \(expected.width)")
        XCTAssertLessThanOrEqual(abs(actual.height - expected.height), tolerance, "\(label) height: \(actual.height) vs \(expected.height)")
    }

    private func anchorToString(_ anchor: Anchor) -> String {
        switch anchor {
        case .topLeft: return "topLeft"
        case .topRight: return "topRight"
        case .bottomLeft: return "bottomLeft"
        case .bottomRight: return "bottomRight"
        }
    }

    // MARK: - Fixture loading

    private struct FixtureRoot: Decodable {
        let cases: [FixtureCase]
    }

    private struct FixtureCase: Decodable {
        let id: String
        let description: String
        let scene: CallScene
        let expected: FixtureExpected
    }

    private struct FixtureExpected: Decodable {
        let mode: String
        let tileCount: Int
        let tiles: [FixtureTile]
        let localPip: FixturePip?
    }

    private struct FixtureTile: Decodable {
        let id: String
        let type: String
        let normalizedFrame: FixtureNormalizedFrame
        let fit: String
    }

    private struct FixturePip: Decodable {
        let participantId: String
        let anchor: String
        let normalizedFrame: FixtureNormalizedFrame
    }

    struct FixtureNormalizedFrame: Decodable {
        let x: CGFloat
        let y: CGFloat
        let width: CGFloat
        let height: CGFloat
    }

    private static func loadFixtures() throws -> [FixtureCase] {
        let fixturePath = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("tests/layout/fixtures/layout_conformance_v1.json")
        let data = try Data(contentsOf: fixturePath)
        return try JSONDecoder().decode(FixtureRoot.self, from: data).cases
    }
}
