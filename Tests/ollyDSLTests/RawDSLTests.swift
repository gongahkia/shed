import XCTest
import ollyCore
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
        let engine = EngineDeclaration.raw("raw-engine") { context in
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
        engine.runRaw(context: RawDSLContext(engineID: "raw-engine"))
        workspace.runRaw(context: RawDSLContext(tag: try? Tag(index: 7)))
        hooks.runRaw(context: RawDSLContext(event: "startup"))

        XCTAssertEqual(recorder.events, ["com.example.App", "raw-engine", "7", "startup"])
        XCTAssertEqual(rule.apply.floating, true)
        XCTAssertEqual(engine.id, "raw-engine")
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
}

private final class RawInvocationRecorder: @unchecked Sendable {
    private(set) var events: [String] = []

    func record(_ event: String?) {
        events.append(event ?? "")
    }
}
