import XCTest
@testable import SerenadaiOS

final class L10nTests: XCTestCase {
    private let languageKey = "language"

    func testL10nUsesExplicitLanguageFromSettings() {
        let defaults = UserDefaults.standard
        let previousValue = defaults.string(forKey: languageKey)
        defer {
            if let previousValue {
                defaults.set(previousValue, forKey: languageKey)
            } else {
                defaults.removeObject(forKey: languageKey)
            }
        }

        defaults.set(AppConstants.languageEn, forKey: languageKey)
        XCTAssertEqual(L10n.commonBack, "Back")

        defaults.set(AppConstants.languageRu, forKey: languageKey)
        XCTAssertEqual(L10n.commonBack, "Назад")
    }
}
