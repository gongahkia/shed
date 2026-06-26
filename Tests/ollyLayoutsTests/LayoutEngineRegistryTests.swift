import CoreGraphics
import XCTest
import ollyCore
import ollyKit
@testable import ollyLayouts

final class LayoutEngineRegistryTests: XCTestCase {
    func testRegistryConstructsEngineByIDAndConfig() async throws {
        let registry = try LayoutEngineRegistry()
        let engineID = LayoutEngineID(rawValue: "test")
        try await registry.register(TestLayoutEngineFactory(id: engineID))

        let engine = try await registry.makeEngine(id: engineID, config: TestLayoutEngine.Config(width: 320))
        let placements = engine.arrange(
            windows: [WindowSnapshot(windowID: 1, frame: .zero)],
            in: CGRect(x: 0, y: 0, width: 800, height: 600),
            focus: nil
        )

        XCTAssertEqual(engine.id, engineID)
        XCTAssertEqual(engine.displayName, "Test")
        XCTAssertEqual(placements, [Placement(windowID: 1, frame: CGRect(x: 0, y: 0, width: 320, height: 600))])
    }

    func testRegistryRejectsDuplicateFactories() async throws {
        let engineID = LayoutEngineID(rawValue: "test")
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(TestLayoutEngineFactory(id: engineID))])

        do {
            try await registry.register(TestLayoutEngineFactory(id: engineID))
            XCTFail("expected duplicate factory error")
        } catch LayoutEngineRegistryError.duplicateFactory(engineID) {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testRegistryReportsUnknownEngine() async throws {
        let registry = try LayoutEngineRegistry()

        do {
            _ = try await registry.makeEngine(id: LayoutEngineID(rawValue: "missing"), config: TestLayoutEngine.Config(width: 1))
            XCTFail("expected unknown engine error")
        } catch LayoutEngineRegistryError.unknownEngine(LayoutEngineID(rawValue: "missing")) {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testRegistryReportsInvalidConfigType() async throws {
        let engineID = LayoutEngineID(rawValue: "test")
        let registry = try LayoutEngineRegistry(factories: [AnyLayoutEngineFactory(TestLayoutEngineFactory(id: engineID))])

        do {
            _ = try await registry.makeEngine(id: engineID, config: "wrong")
            XCTFail("expected invalid config type error")
        } catch LayoutEngineRegistryError.invalidConfigType(engineID, "Config", "String") {
        } catch {
            XCTFail("unexpected error: \(error)")
        }
    }

    func testRegisteredEngineIDsAreSortedAndUnregisterRemovesFactory() async throws {
        let first = LayoutEngineID(rawValue: "a")
        let second = LayoutEngineID(rawValue: "b")
        let registry = try LayoutEngineRegistry()

        try await registry.register(TestLayoutEngineFactory(id: second))
        try await registry.register(TestLayoutEngineFactory(id: first))
        let ids = await registry.registeredEngineIDs()
        await registry.unregister(id: first)
        let remainingIDs = await registry.registeredEngineIDs()

        XCTAssertEqual(ids, [first, second])
        XCTAssertEqual(remainingIDs, [second])
    }
}

private struct TestLayoutEngine: LayoutEngine {
    struct Config {
        let width: CGFloat
    }

    let id: LayoutEngineID
    let displayName = "Test"
    let config: Config

    func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        windows.map { window in
            Placement(
                windowID: window.windowID,
                frame: CGRect(x: bounds.minX, y: bounds.minY, width: config.width, height: bounds.height)
            )
        }
    }
}

private struct TestLayoutEngineFactory: LayoutEngineFactory {
    let id: LayoutEngineID
    let displayName = "Test"

    func makeEngine(config: TestLayoutEngine.Config) throws -> TestLayoutEngine {
        TestLayoutEngine(id: id, config: config)
    }
}
