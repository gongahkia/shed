import XCTest
@testable import ollyApp

final class LocalizationTests: XCTestCase {
    func testEnglishStringsResourceIsBundled() throws {
        let url = try XCTUnwrap(L10n.bundle.url(
            forResource: "Localizable",
            withExtension: "strings",
            subdirectory: "en.lproj"
        ))
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testEnglishStringsLoadFromResourceBundle() {
        XCTAssertEqual(L10n.s("__olly_localization_probe__", "test probe"), "localized")
        XCTAssertEqual(L10n.f("Step %d of %d", "test progress", 1, 6), "Step 1 of 6")
    }
}
