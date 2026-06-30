import Carbon.HIToolbox
import XCTest
import ollyDSL
@testable import ollyApp

final class SettingsKeybindsControllerTests: XCTestCase {
    func testStartupDiagnosticsStoresCollisionReportAndNotifies() {
        let collisionHotKey = ExternalHotKey(
            owner: .skhd,
            chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_J), modifiers: UInt32(optionKey)),
            detail: "skhdrc:1"
        )
        let store = HotKeyDiagnosticsStore()
        let notifier = RecordingHotKeyConflictNotifier()
        let diagnostics = HotKeyStartupDiagnostics(
            loadConfig: {
                Config {
                    Keybinds {
                        Keybind(KeyChord([.option], .j), do: .focus(.next))
                    }
                }
            },
            detector: HotKeyCollisionDetector {
                HotKeyScanResult(hotKeys: [collisionHotKey])
            },
            notifier: notifier,
            store: store
        )

        diagnostics.run()

        XCTAssertEqual(store.currentReport()?.collisions.count, 1)
        XCTAssertEqual(store.currentReport()?.collisions.first?.externalDetail, "skhdrc:1")
        XCTAssertEqual(notifier.collisions.count, 1)
    }

    func testRendererBuildsRowsStatusAndSourceErrors() {
        let report = HotKeyCollisionReport(
            collisions: [
                HotKeyCollision(
                    chord: HotKeyChord(keyCode: UInt32(kVK_ANSI_J), modifiers: UInt32(optionKey)),
                    action: .focus(.next),
                    externalOwner: .skhd,
                    externalDetail: "skhdrc:1"
                )
            ],
            sourceErrors: [
                HotKeySourceError(owner: .karabinerElements, detail: "invalid json")
            ]
        )

        let rows = SettingsKeybindDiagnosticsRenderer.rows(from: report)

        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].owner, "skhd")
        XCTAssertEqual(rows[0].detail, "skhdrc:1")
        XCTAssertEqual(SettingsKeybindDiagnosticsRenderer.status(from: report), "1 keybind conflict detected.")
        XCTAssertEqual(
            SettingsKeybindDiagnosticsRenderer.sourceErrors(from: report),
            "Karabiner-Elements: invalid json"
        )
    }
}

private final class RecordingHotKeyConflictNotifier: HotKeyConflictNotifier {
    var collisions: [HotKeyCollision] = []

    func notify(collisions: [HotKeyCollision]) {
        self.collisions = collisions
    }
}
