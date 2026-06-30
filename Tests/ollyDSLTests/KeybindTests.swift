import Carbon.HIToolbox
import XCTest
@testable import ollyDSL

final class KeybindTests: XCTestCase {
    func testKeybindBuilderCollectsBindings() {
        let reload = Keybind(KeyChord([.command, .shift], .r), do: .reload)
        let focus = Keybind(KeyChord([.option], .rightArrow), do: .focus(.right))

        let keybinds = Keybinds {
            reload
            focus
        }

        XCTAssertEqual(keybinds.bindings, [reload, focus])
    }

    func testConfigStoresKeybindSection() {
        let reload = Keybind(KeyChord([.command], .r), do: .reload)
        let config = Config {
            Keybinds {
                reload
            }
        }

        XCTAssertEqual(config.keybinds.bindings, [reload])
    }

    func testOverlayActionsAreStorableInKeybinds() {
        let grid = Keybind(KeyChord([.command, .shift], .slash), do: .showGridOverlay)
        let explicit = Keybind(KeyChord([.command], .space), do: .showOverlay(.grid))

        let keybinds = Keybinds {
            grid
            explicit
        }

        XCTAssertEqual(keybinds.bindings.map(\.action), [.showGridOverlay, .showOverlay(.grid)])
    }

    func testCarbonRegistrationsUseSequentialIDsAndCarbonFlags() {
        let keybinds = Keybinds {
            Keybind(KeyChord([.command, .shift], .space), do: .reload)
            Keybind(KeyChord([.control, .option], .leftArrow), do: .focus(.left))
        }

        let registrations = keybinds.carbonRegistrations(signature: 0x54455354)

        XCTAssertEqual(registrations.count, 2)
        XCTAssertEqual(registrations[0].id, 1)
        XCTAssertEqual(registrations[0].signature, 0x54455354)
        XCTAssertEqual(registrations[0].keyCode, UInt32(kVK_Space))
        XCTAssertEqual(registrations[0].modifiers, UInt32(cmdKey | shiftKey))
        XCTAssertEqual(registrations[0].action, .reload)
        XCTAssertEqual(registrations[1].id, 2)
        XCTAssertEqual(registrations[1].keyCode, UInt32(kVK_LeftArrow))
        XCTAssertEqual(registrations[1].modifiers, UInt32(controlKey | optionKey))
        XCTAssertEqual(registrations[1].action, .focus(.left))
    }
}
