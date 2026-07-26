import AppKit
import Carbon.HIToolbox
import XCTest
import ollyDSL
import ollyKit
import ollyRuntime
@testable import ollyApp

final class CheatsheetControllerTests: XCTestCase {
    func testCatalogGroupsEveryBindingIntoExpectedBuckets() {
        let entries = CheatsheetCatalog.entries(from: Self.sampleKeybinds())

        XCTAssertEqual(entries.count, 12)
        XCTAssertEqual(Set(entries.map(\.category)), Set(CheatsheetCategory.allCases))
    }

    func testKeyActionMatchesCommandSlashAndEscape() throws {
        XCTAssertEqual(
            CheatsheetKeyAction.action(for: try keyEvent(keyCode: kVK_ANSI_Slash, flags: .command), isVisible: false),
            .show
        )
        XCTAssertEqual(
            CheatsheetKeyAction.action(for: try keyEvent(keyCode: kVK_Escape), isVisible: true),
            .close
        )
        XCTAssertEqual(
            CheatsheetKeyAction.action(for: try keyEvent(keyCode: kVK_Escape), isVisible: false),
            .none
        )
    }

    @MainActor
    func testViewRendersAllRows() {
        let entries = CheatsheetCatalog.entries(from: Self.sampleKeybinds())
        let view = CheatsheetView()

        view.configure(entries: entries)

        XCTAssertEqual(view.rowCount, 12)
    }

    @MainActor
    func testControllerShowsRowsFromKeybindProvider() async {
        let controller = CheatsheetController(
            runtime: OllyRuntime(),
            keybindProvider: { Self.sampleKeybinds() }
        )
        defer {
            controller.stop()
        }

        await controller.show()

        XCTAssertEqual(controller.rowCount, 12)
    }

    private static func sampleKeybinds() -> Keybinds {
        Keybinds {
            Keybind(KeyChord([.option], .h), do: .focus(.left))
            Keybind(KeyChord([.option], .l), do: .swap(.right))
            Keybind(KeyChord([.option], .k), do: .move(.up))
            Keybind(KeyChord([.command], .one), do: .switchTag(1))
            Keybind(KeyChord([.command], .two), do: .toggleTag(2))
            Keybind(KeyChord([.command], .three), do: .moveWindowToTag(3))
            Keybind(KeyChord([.command, .option], .b), do: .setEngine(LayoutEngineID(rawValue: "bsp")))
            Keybind(KeyChord([.command, .option], .space), do: .cycleEngine)
            Keybind(KeyChord([.command, .shift], .slash), do: .showGridOverlay)
            Keybind(KeyChord([.command], .m), do: .macro("daily"))
            Keybind(KeyChord([.command], .e), do: .shell("echo ok", label: "echo"))
            Keybind(KeyChord([.command], .r), do: .raw("custom.reload"))
        }
    }

    private func keyEvent(
        keyCode: Int,
        flags: NSEvent.ModifierFlags = []
    ) throws -> NSEvent {
        try XCTUnwrap(NSEvent.keyEvent(
            with: .keyDown,
            location: .zero,
            modifierFlags: flags,
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            characters: "",
            charactersIgnoringModifiers: "",
            isARepeat: false,
            keyCode: UInt16(keyCode)
        ))
    }
}
