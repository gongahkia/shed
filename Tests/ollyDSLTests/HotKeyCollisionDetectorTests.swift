import Carbon.HIToolbox
import XCTest
@testable import ollyDSL

final class HotKeyCollisionDetectorTests: XCTestCase {
    func testDetectorReportsExternalCollisionWithDSLKeybind() {
        let keybinds = Keybinds {
            Keybind(KeyChord([.option], .j), do: .focus(.next))
            Keybind(KeyChord([.command], .space), do: .reload)
        }
        let skhdHotKey = ExternalHotKey(
            owner: .skhd,
            chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_J), modifiers: UInt32(optionKey)),
            detail: "skhdrc:1"
        )
        let detector = HotKeyCollisionDetector {
            HotKeyScanResult(hotKeys: [skhdHotKey])
        }

        let report = detector.report(for: keybinds)

        XCTAssertEqual(report.collisions.count, 1)
        XCTAssertEqual(report.collisions[0].chord, skhdHotKey.chord)
        XCTAssertEqual(report.collisions[0].action, .focus(.next))
        XCTAssertEqual(report.collisions[0].externalOwner, .skhd)
    }

    func testKarabinerParserReadsSelectedProfileComplexModification() throws {
        let data = Data("""
        {
          "profiles": [
            {
              "selected": false,
              "complex_modifications": {
                "rules": [
                  {
                    "description": "inactive",
                    "manipulators": [
                      {
                        "type": "basic",
                        "from": {
                          "key_code": "spacebar",
                          "modifiers": { "mandatory": ["left_command"] }
                        }
                      }
                    ]
                  }
                ]
              }
            },
            {
              "selected": true,
              "complex_modifications": {
                "rules": [
                  {
                    "description": "olly focus",
                    "manipulators": [
                      {
                        "type": "basic",
                        "from": {
                          "key_code": "j",
                          "modifiers": { "mandatory": ["left_option"] }
                        }
                      }
                    ]
                  }
                ]
              }
            }
          ]
        }
        """.utf8)

        let hotKeys = try KarabinerHotKeyParser.parse(data: data, sourceURL: URL(fileURLWithPath: "/tmp/karabiner.json"))

        XCTAssertEqual(hotKeys, [
            ExternalHotKey(
                owner: .karabinerElements,
                chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_J), modifiers: UInt32(optionKey)),
                detail: "olly focus in karabiner.json"
            )
        ])
    }

    func testSkhdParserReadsCommonBindings() {
        let source = """
        # ignored
        alt - j : /opt/homebrew/bin/ollyctl focus next
        alt + shift - 1 : /opt/homebrew/bin/ollyctl move-to-tag 1
        """

        let hotKeys = SkhdHotKeyParser.parse(source: source, sourceURL: URL(fileURLWithPath: "/tmp/skhdrc"))

        XCTAssertEqual(hotKeys, [
            ExternalHotKey(
                owner: .skhd,
                chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_J), modifiers: UInt32(optionKey)),
                detail: "skhdrc:2"
            ),
            ExternalHotKey(
                owner: .skhd,
                chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_1), modifiers: UInt32(optionKey | shiftKey)),
                detail: "skhdrc:3"
            )
        ])
    }
}
