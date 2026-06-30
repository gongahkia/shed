import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyDSL

final class RawDSLTests: XCTestCase {
    func testRawKeybindStoresHandlerAndEncodesStableLabel() throws {
        let recorder = RawInvocationRecorder()
        let keybind = Keybind.raw(KeyChord([.command], .r), label: "reload") { context in
            recorder.record(context.event)
        }

        keybind.runRaw(context: RawDSLContext(event: "reload"))
        let data = try JSONEncoder().encode(keybind)
        let decoded = try JSONDecoder().decode(Keybind.self, from: data)

        XCTAssertEqual(recorder.events, ["reload"])
        XCTAssertEqual(decoded.action, .raw("reload"))
        XCTAssertNil(decoded.rawHandler)
        XCTAssertEqual(decoded, keybind)
    }

    func testRawRuleEngineWorkspaceAndHooksReceiveContext() {
        let recorder = RawInvocationRecorder()
        let rule = Rule.raw(
            match: RuleMatch(bundleID: "com.example.App"),
            apply: RuleApply(floating: true),
            label: "rule"
        ) { context in
            recorder.record(context.ruleContext?.bundleID)
        }
        let rawEngineID = LayoutEngineID(rawValue: "raw-engine")
        let engine = EngineDeclaration.raw(rawEngineID) { context in
            recorder.record(context.engineID?.rawValue)
        }
        let workspace = NamedTagDeclaration.raw("scratch") { context in
            recorder.record(context.tag.map { String($0.index) })
        }
        let hooks = Hooks {
            .raw("startup") { context in
                recorder.record(context.event)
            }
        }

        rule.runRaw(context: RawDSLContext(ruleContext: RuleContext(bundleID: "com.example.App")))
        engine.runRaw(context: RawDSLContext(engineID: rawEngineID))
        workspace.runRaw(context: RawDSLContext(tag: try? Tag(index: 7)))
        hooks.runRaw(context: RawDSLContext(event: "startup"))

        XCTAssertEqual(recorder.events, ["com.example.App", "raw-engine", "7", "startup"])
        XCTAssertEqual(rule.apply.floating, true)
        XCTAssertEqual(engine.id, rawEngineID)
        XCTAssertEqual(workspace.name, "scratch")
        XCTAssertEqual(hooks.declarations.map(\.label), ["startup"])
    }

    func testConfigCanSerializeRawHookLabelsWithoutClosures() throws {
        let config = Config {
            Hooks {
                .raw("reload") { _ in }
            }
        }

        let data = try JSONEncoder().encode(config)
        let decoded = try JSONDecoder().decode(Config.self, from: data)

        XCTAssertEqual(decoded.hooks.declarations.map(\.label), ["reload"])
        XCTAssertNil(decoded.hooks.declarations.first?.rawHandler)
        XCTAssertEqual(decoded, config)
    }

    func testTypedHooksReceiveTypedContextsAndSerializeLabels() throws {
        let recorder = RawInvocationRecorder()
        let hooks = Hooks {
            onTagSwitch { context in
                recorder.record("tag:\(context.activeTags.rawValue)")
            }
            onDisplayChange("display.trace") { context in
                recorder.record("display:\(context.change.displayID)")
            }
            onWindowAppeared { context in
                recorder.record(context.window.bundleID)
            }
            onAXPermissionChanged { context in
                recorder.record(context.status.wireValue)
            }
            onWindowClosed { context in
                recorder.record("closed:\(context.window.id)")
            }
            onConfigReload { context in
                recorder.record("reload:\(context.current.version.rawValue)")
            }
            onEngineChange { context in
                recorder.record("engine:\(context.currentEngineID.rawValue)")
            }
            onFullscreenEnter { context in
                recorder.record("enter:\(context.window.id):\(context.didEnter)")
            }
            onFullscreenExit { context in
                recorder.record("exit:\(context.window.id):\(context.didEnter)")
            }
        }
        let window = WindowState(id: 4, processID: 40, bundleID: "com.example.App", frame: .zero)

        hooks.runTagSwitch(
            context: TagSwitchHookContext(displayID: 1, previousTags: [], activeTags: TagSet(rawValue: 2))
        )
        hooks.runDisplayChange(
            context: DisplayChangeHookContext(
                change: DisplayChange(displayID: 3, flags: CGDisplayChangeSummaryFlags(), displays: [])
            )
        )
        hooks.runWindowAppeared(context: WindowAppearedHookContext(window: window))
        hooks.runAXPermissionChanged(context: AXPermissionHookContext(status: .missing))
        hooks.runWindowClosed(context: WindowClosedHookContext(window: window))
        hooks.runConfigReload(context: ConfigReloadHookContext(previous: Config(), current: Config(), sourceURL: nil))
        hooks.runEngineChange(context: EngineChangeHookContext(
            displayID: 1,
            tag: try Tag(index: 0),
            previousEngineID: nil,
            currentEngineID: LayoutEngineID(rawValue: "next")
        ))
        hooks.runFullscreenEnter(context: FullscreenHookContext(window: window, didEnter: true))
        hooks.runFullscreenExit(context: FullscreenHookContext(window: window, didEnter: false))

        let data = try JSONEncoder().encode(hooks)
        let decoded = try JSONDecoder().decode(Hooks.self, from: data)

        XCTAssertEqual(recorder.events, [
            "tag:2",
            "display:3",
            "com.example.App",
            "missing",
            "closed:4",
            "reload:v1",
            "engine:next",
            "enter:4:true",
            "exit:4:false"
        ])
        XCTAssertEqual(
            decoded.declarations.map(\.label),
            [
                "onTagSwitch",
                "display.trace",
                "onWindowAppeared",
                "onAXPermissionChanged",
                "onWindowClosed",
                "onConfigReload",
                "onEngineChange",
                "onFullscreenEnter",
                "onFullscreenExit"
            ]
        )
        XCTAssertEqual(
            decoded.declarations.map(\.kind),
            [
                .tagSwitch,
                .displayChange,
                .windowAppeared,
                .axPermissionChanged,
                .windowClosed,
                .configReload,
                .engineChange,
                .fullscreenEnter,
                .fullscreenExit
            ]
        )
        XCTAssertEqual(decoded, hooks)
        XCTAssertNil(decoded.declarations.first?.tagSwitchHandler)
    }
}

private final class RawInvocationRecorder: @unchecked Sendable {
    private(set) var events: [String] = []

    func record(_ event: String?) {
        events.append(event ?? "")
    }
}
