import CoreGraphics
import Foundation
import ollyCore
import ollyDiagnostics
import ollyDSL
import ollyKit
import ollyLayouts

@main
enum PerfBench {
    static func main() async throws {
        do {
            let options = try PerfBenchOptions(arguments: Array(CommandLine.arguments.dropFirst()))
            let report = try await BenchmarkRunner(options: options).run()
            try ReportWriter.write(report, to: options.outputPath)
            if options.failOnBudget, report.diagnostics.hasFailures {
                throw PerfBenchError.budgetFailed(
                    failed: report.diagnostics.failedCount,
                    missing: report.diagnostics.missingCount
                )
            }
        } catch {
            FileHandle.standardError.write(Data("PerfBench failed: \(error)\n".utf8))
            throw error
        }
    }
}

struct PerfBenchOptions {
    var iterations = 50
    var windowCount = 50
    var soakEvents = 5_000
    var outputPath: String?
    var failOnBudget = false

    init(arguments: [String]) throws {
        var index = 0
        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--iterations":
                iterations = try Self.intValue(arguments, after: &index, name: argument)
            case "--windows":
                windowCount = try Self.intValue(arguments, after: &index, name: argument)
            case "--soak-events":
                soakEvents = try Self.intValue(arguments, after: &index, name: argument)
            case "--output":
                outputPath = try Self.stringValue(arguments, after: &index, name: argument)
            case "--fail-on-budget":
                failOnBudget = true
            case "--help", "-h":
                throw PerfBenchError.help
            default:
                throw PerfBenchError.unknownArgument(argument)
            }
            index += 1
        }
        iterations = max(1, iterations)
        windowCount = max(1, windowCount)
        soakEvents = max(1, soakEvents)
    }

    private static func intValue(_ arguments: [String], after index: inout Int, name: String) throws -> Int {
        let value = try stringValue(arguments, after: &index, name: name)
        guard let parsed = Int(value) else {
            throw PerfBenchError.invalidValue(name, value)
        }
        return parsed
    }

    private static func stringValue(_ arguments: [String], after index: inout Int, name: String) throws -> String {
        index += 1
        guard arguments.indices.contains(index) else {
            throw PerfBenchError.missingValue(name)
        }
        return arguments[index]
    }
}

enum PerfBenchError: Error, CustomStringConvertible {
    case help
    case missingValue(String)
    case invalidValue(String, String)
    case unknownArgument(String)
    case budgetFailed(failed: Int, missing: Int)

    var description: String {
        switch self {
        case .help:
            return [
                "usage: PerfBench [--iterations N] [--windows N]",
                "[--soak-events N] [--output path] [--fail-on-budget]"
            ].joined(separator: " ")
        case let .missingValue(name):
            return "\(name) requires a value"
        case let .invalidValue(name, value):
            return "\(name) requires an integer, got \(value)"
        case let .unknownArgument(argument):
            return "unknown argument \(argument)"
        case let .budgetFailed(failed, missing):
            return "performance budget failed: \(failed) failed, \(missing) missing"
        }
    }
}

struct BenchReport: Encodable {
    let generatedAt: String
    let iterations: Int
    let windowCount: Int
    let soakEvents: Int
    let scenarios: [PerformanceScenarioResult]
    let diagnostics: PerformanceBudgetDiagnostics
}

final class Sink {
    private(set) var value = 0

    func consume(_ value: Int) {
        self.value ^= value
    }
}

struct BenchmarkRunner {
    let options: PerfBenchOptions
    let sink = Sink()

    func run() async throws -> BenchReport {
        let scenarios = [
            try await measure("cold-start-proxy", runColdStartProxy),
            try await measure("hotkey-to-move-proxy", runHotkeyToMoveProxy),
            try await measure("layout-recompute-\(options.windowCount)-windows", runLayoutRecompute),
            try await measure("state-snapshot-\(options.windowCount)-windows", runStateSnapshotProxy),
            try await measure("tag-switch-\(options.windowCount)-windows", runTagSwitch),
            try await measure("recovery-journal-\(options.windowCount)-windows", runRecoveryJournal),
            try await measure("wake-from-sleep-proxy", runWakeFromSleepProxy),
            try await measure("soak-\(options.soakEvents)-events", runSoak)
        ]
        let diagnostics = PerformanceBudgetEvaluator.evaluate(
            scenarios: scenarios,
            budgets: PerformanceBudgetCatalog.v0ProxyBudgets(
                windowCount: options.windowCount,
                soakEvents: options.soakEvents
            )
        )
        sink.consume(scenarios.count + diagnostics.passedCount)
        return BenchReport(
            generatedAt: ISO8601DateFormatter().string(from: Date()),
            iterations: options.iterations,
            windowCount: options.windowCount,
            soakEvents: options.soakEvents,
            scenarios: scenarios,
            diagnostics: diagnostics
        )
    }

    private func measure(_ name: String, _ block: () async throws -> Void) async throws -> PerformanceScenarioResult {
        var samples: [Double] = []
        for _ in 0..<options.iterations {
            let start = ContinuousClock.now
            try await block()
            samples.append(Self.milliseconds(from: start.duration(to: ContinuousClock.now)))
        }
        return PerformanceScenarioResult(
            name: name,
            unit: "milliseconds",
            sampleCount: samples.count,
            p50: percentile(samples, 0.50),
            p95: percentile(samples, 0.95),
            p99: percentile(samples, 0.99),
            min: samples.min() ?? 0,
            max: samples.max() ?? 0
        )
    }

    private func runColdStartProxy() async throws {
        let registry = try LayoutEngineRegistry(factories: [
            AnyLayoutEngineFactory(FloatingLayoutEngineFactory()),
            AnyLayoutEngineFactory(MasterStackLayoutEngineFactory()),
            AnyLayoutEngineFactory(ManualLayoutEngineFactory()),
            AnyLayoutEngineFactory(BSPLayoutEngineFactory()),
            AnyLayoutEngineFactory(NiriScrollLayoutEngineFactory())
        ])
        let tags = TagStore(defaultActiveTags: TagSet(try Tag(index: 0)))
        let store = WindowStore()
        let config = Config()
        let ids = await registry.registeredEngineIDs()
        sink.consume(ids.count + config.engines.engines.count + (await store.count))
        _ = await tags.setActiveTags(TagSet(try Tag(index: 1)), on: 1)
    }

    private func runHotkeyToMoveProxy() async throws {
        let focus = FocusStack()
        let keybinds = Keybinds {
            Keybind(KeyChord([.option], .j), do: .focus(.next))
            Keybind(KeyChord([.control, .option], .j), do: .move(.down))
        }
        let registrations = keybinds.carbonRegistrations()
        for id in 1...options.windowCount {
            await focus.recordFocus(windowID: UInt32(id), displayID: 1, tagMask: 1)
        }
        let restored = await focus.restoreFocus(
            displayID: 1,
            tagMask: 1,
            availableWindows: Set((1...options.windowCount).map(UInt32.init))
        ) { _ in true }
        sink.consume(registrations.count + Int(restored ?? 0))
    }

    private func runTagSwitch() async throws {
        let active = try Tag(index: 0)
        let inactive = try Tag(index: 1)
        let store = WindowStore()
        let tags = TagStore(defaultActiveTags: TagSet(active))
        let dispatcher = TagDispatcher(
            windowStore: store,
            tagStore: tags,
            displayProvider: { displays() },
            moveWindow: { _, _ in }
        )
        for window in windows(count: options.windowCount, active: active, inactive: inactive) {
            await store.upsert(window)
        }
        await tags.setActiveTags(TagSet(inactive), on: 1)
        let moves = await dispatcher.apply(displayID: 1)
        sink.consume(moves.count)
    }

    private func runLayoutRecompute() async throws {
        let engine = MasterStackLayoutEngine()
        let snapshots = windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 0))
            .map(WindowSnapshot.init)
        let placements = engine.arrange(
            windows: snapshots,
            in: CGRect(x: 0, y: 0, width: 1_470, height: 918),
            focus: snapshots.first?.windowID
        )
        sink.consume(placements.count)
    }

    private func runStateSnapshotProxy() async throws {
        let store = WindowStore()
        let tags = TagStore(defaultActiveTags: TagSet(try Tag(index: 0)))
        for window in windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 1)) {
            await store.upsert(window)
        }
        let displayStates = await tags.allStates()
        let windows = await store.allWindows()
        sink.consume(displayStates.count + windows.count)
    }

    private func runRecoveryJournal() async throws {
        let stateURL = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("recovery.json")
        let journal = WindowRecoveryJournal(stateURL: stateURL)
        defer {
            try? FileManager.default.removeItem(at: stateURL.deletingLastPathComponent())
        }
        let parkedFrame = CGRect(x: -32_000, y: -32_000, width: 640, height: 420)
        for window in windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 1)) {
            try await journal.record(window: window, parkedFrame: parkedFrame)
        }
        let state = try await journal.load()
        try await journal.remove(windowIDs: state.entries.map(\.windowID))
        sink.consume(state.entries.count)
    }

    private func runWakeFromSleepProxy() async throws {
        let store = WindowStore()
        let focus = FocusStack()
        let provider = FixedNativeSpaceProvider(spaceID: NativeSpaceID(rawValue: 7))
        for window in windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 1)) {
            await store.upsert(window)
            await focus.recordFocus(windowID: window.id, displayID: window.displayID ?? 1, tagMask: window.tagMask)
        }
        let invariant = NativeSpaceInvariant(windowStore: store, spaceProvider: provider)
        let result = await invariant.verify(expectedSpaceID: NativeSpaceID(rawValue: 7))
        let restored = await focus.restoreFocus(
            displayID: 1,
            tagMask: 1,
            availableWindows: Set((1...options.windowCount).map(UInt32.init))
        ) { _ in true }
        sink.consume(result.issues.count + Int(restored ?? 0))
    }

    private func runSoak() async throws {
        let store = WindowStore()
        for index in 0..<options.soakEvents {
            let id = WindowID(index % max(1, options.windowCount) + 1)
            await store.upsert(WindowState(id: id, processID: 42, displayID: 1, tagMask: 1, frame: frame(index)))
            if index % 7 == 0 {
                _ = await store.windows(onDisplay: 1)
            }
        }
        sink.consume(await store.count)
    }

    private func windows(count: Int, active: Tag, inactive: Tag) -> [WindowState] {
        (1...count).map { index in
            let tag = index.isMultiple(of: 2) ? active : inactive
            return WindowState(
                id: WindowID(index),
                processID: 42,
                displayID: 1,
                tagMask: TagSet(tag).rawValue,
                frame: frame(index)
            )
        }
    }

    private func displays() -> [Display] {
        [
            Display(
                id: 1,
                frame: CGRect(x: 0, y: 0, width: 1_470, height: 956),
                visibleFrame: CGRect(x: 0, y: 38, width: 1_470, height: 918),
                scaleFactor: 2,
                localizedName: "Built-in",
                isMain: true
            ),
            Display(
                id: 2,
                frame: CGRect(x: 1_470, y: 0, width: 2_560, height: 1_440),
                visibleFrame: CGRect(x: 1_470, y: 0, width: 2_560, height: 1_405),
                scaleFactor: 2,
                localizedName: "External",
                isMain: false
            )
        ]
    }

    private func frame(_ index: Int) -> CGRect {
        CGRect(x: CGFloat(index % 8) * 40, y: CGFloat(index % 5) * 30, width: 640, height: 420)
    }

    private static func milliseconds(from duration: Duration) -> Double {
        let components = duration.components
        return Double(components.seconds) * 1_000 + Double(components.attoseconds) / 1_000_000_000_000_000
    }

    private func percentile(_ values: [Double], _ percentile: Double) -> Double {
        let sorted = values.sorted()
        guard !sorted.isEmpty else {
            return 0
        }
        let index = max(0, min(sorted.count - 1, Int(ceil(Double(sorted.count) * percentile)) - 1))
        return sorted[index]
    }
}

struct FixedNativeSpaceProvider: WindowNativeSpaceProviding {
    let spaceID: NativeSpaceID

    func nativeSpaceID(for window: WindowState) async -> NativeSpaceID? {
        spaceID
    }
}

enum ReportWriter {
    static func write(_ report: BenchReport, to path: String?) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(report)
        if let path {
            try data.write(to: URL(fileURLWithPath: path))
        } else {
            FileHandle.standardOutput.write(data)
            FileHandle.standardOutput.write(Data("\n".utf8))
        }
    }
}
