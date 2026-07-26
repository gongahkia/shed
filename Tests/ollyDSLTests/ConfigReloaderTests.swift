import Foundation
import XCTest
import ollyCore
@testable import ollyDSL

final class ConfigReloaderTests: XCTestCase {
    func testReloadRetainsPreviousConfigOnFailure() throws {
        let loaded = LoadedConfig(
            config: Config { Workspaces { Tag.named("code") } },
            libraryURL: URL(fileURLWithPath: "/tmp/Config.dylib"),
            contentHash: "ok",
            didCompile: true
        )
        let script = ReloadScript(results: [.success(loaded), .failure(TestError.compileFailed)])
        let events = ReloadEvents()
        let reloader = ConfigReloader(
            sourceURL: temporaryDirectory().appendingPathComponent("Config.swift"),
            load: script.next,
            notify: { event in events.append(event) }
        )

        let first = reloader.reloadNow()
        let second = reloader.reloadNow()

        XCTAssertEqual(first, .reloaded(loaded))
        XCTAssertEqual(reloader.currentConfig, loaded)
        XCTAssertEqual(second, .failed(ConfigReloadFailure(message: "compileFailed", retainedConfig: loaded)))
        XCTAssertEqual(reloader.currentConfig, loaded)
        XCTAssertEqual(events.values, [first, second])
    }

    func testStartCreatesWatchDirectoryAndStopIsIdempotent() throws {
        let directory = temporaryDirectory().appendingPathComponent("olly", isDirectory: true)
        let sourceURL = directory.appendingPathComponent("Config.swift")
        let reloader = ConfigReloader(
            sourceURL: sourceURL,
            load: { throw TestError.compileFailed }
        )

        try reloader.start()
        reloader.stop()
        reloader.stop()

        XCTAssertTrue(FileManager.default.fileExists(atPath: directory.path))
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("ollyDSLTests-\(UUID().uuidString)", isDirectory: true)
    }
}

private final class ReloadScript: @unchecked Sendable {
    private var results: [Result<LoadedConfig, Error>]
    private let lock = NSLock()

    init(results: [Result<LoadedConfig, Error>]) {
        self.results = results
    }

    func next() throws -> LoadedConfig {
        lock.lock()
        defer { lock.unlock() }
        return try results.removeFirst().get()
    }
}

private final class ReloadEvents: @unchecked Sendable {
    private(set) var values: [ConfigReloadEvent] = []
    private let lock = NSLock()

    func append(_ event: ConfigReloadEvent) {
        lock.lock()
        defer { lock.unlock() }
        values.append(event)
    }
}

private enum TestError: Error, CustomStringConvertible {
    case compileFailed

    var description: String {
        "compileFailed"
    }
}
