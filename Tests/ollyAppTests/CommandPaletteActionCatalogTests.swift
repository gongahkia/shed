import Foundation
import XCTest
@testable import ollyApp

final class CommandPaletteActionCatalogTests: XCTestCase {
    func testMacroActionsAreDiscoveredFromDisk() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("olly-palette-\(UUID().uuidString)", isDirectory: true)
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let macro = """
        {"name":"workflow1","createdAt":"1970-01-01T00:00:00Z","recordedDurationMs":25,"commandCount":2,"commands":[]}
        """
        try Data(macro.utf8).write(to: directory.appendingPathComponent("workflow1.json"))

        let actions = CommandPaletteActionCatalog(macroDirectory: directory).actions()
        let action = try XCTUnwrap(actions.first { $0.id == "macro-workflow1" })

        XCTAssertEqual(action.title, "Run Macro: workflow1")
        XCTAssertEqual(action.detail, "2 commands")
        XCTAssertEqual(action.command.name.rawValue, "macro-run")
    }
}
