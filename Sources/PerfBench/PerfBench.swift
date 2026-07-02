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
    case thumbnailImageUnavailable

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
        case .thumbnailImageUnavailable:
            return "thumbnail image unavailable"
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
            try await measure("scratchpad-toggle-latency", runScratchpadToggleLatency),
            try await measure("permission-revoke-recovery", runPermissionRevokeRecovery),
            try await measure("space-drift-verify", runSpaceDriftVerify),
            try await measureFocusRateLimitEval(),
            try await measureRatio(
                "animated-arrange",
                baseline: runNonAnimatedArrangeBaseline,
                candidate: runAnimatedArrange
            ),
            try await measure("thumbnail-generation-20-windows", runThumbnailGeneration),
            try await measure("thumbnail-cache-fill-20-windows", runThumbnailGeneration),
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

    private func measureFocusRateLimitEval() async throws -> PerformanceScenarioResult {
        let limiter = FocusRateLimiter()
        var samples: [Double] = []
        for index in 0..<options.iterations {
            let start = ContinuousClock.now
            let accepted = await limiter.shouldAccept(processID: pid_t(index + 1), isUserInitiated: false)
            samples.append(Self.milliseconds(from: start.duration(to: ContinuousClock.now)))
            sink.consume(accepted ? index : 0)
        }
        return PerformanceScenarioResult(
            name: "focus-rate-limit-eval",
            unit: "milliseconds",
            sampleCount: samples.count,
            p50: percentile(samples, 0.50),
            p95: percentile(samples, 0.95),
            p99: percentile(samples, 0.99),
            min: samples.min() ?? 0,
            max: samples.max() ?? 0
        )
    }

    private func measureRatio(
        _ name: String,
        baseline: () async throws -> Void,
        candidate: () async throws -> Void
    ) async throws -> PerformanceScenarioResult {
        var samples: [Double] = []
        for _ in 0..<options.iterations {
            let baselineStart = ContinuousClock.now
            try await baseline()
            let baselineMilliseconds = Self.milliseconds(from: baselineStart.duration(to: ContinuousClock.now))
            let candidateStart = ContinuousClock.now
            try await candidate()
            let candidateMilliseconds = Self.milliseconds(from: candidateStart.duration(to: ContinuousClock.now))
            samples.append(candidateMilliseconds / max(baselineMilliseconds, 0.001))
        }
        return PerformanceScenarioResult(
            name: name,
            unit: "baseline-ratio",
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

    private func runNonAnimatedArrangeBaseline() async throws {
        try await runRepeatedArrange(animated: false)
    }

    private func runAnimatedArrange() async throws {
        try await runRepeatedArrange(animated: true)
    }

    private func runRepeatedArrange(animated: Bool) async throws {
        let engine = MasterStackLayoutEngine()
        let snapshots = windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 0))
            .map(WindowSnapshot.init)
        var consumed = 0, didMeasureAnimation = false
        for _ in 0..<32 {
            let placements = engine.arrange(
                windows: snapshots,
                in: CGRect(x: 0, y: 0, width: 1_470, height: 918),
                focus: snapshots.first?.windowID
            )
            consumed += placements.count
            if animated, !didMeasureAnimation, let placement = placements.first {
                let frames = WindowFrameAnimation.frames(
                    from: placement.frame.offsetBy(dx: -12, dy: -8),
                    to: placement.frame,
                    duration: 0.12,
                    frameInterval: 1.0 / 60.0,
                    curve: .easeOut
                )
                consumed += frames.count
                didMeasureAnimation = true
            }
        }
        sink.consume(consumed)
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

    private func runScratchpadToggleLatency() async throws {
        let stateURL = temporaryStateURL("scratchpads.json")
        let registry = ScratchpadRegistry(stateURL: stateURL)
        defer {
            try? FileManager.default.removeItem(at: stateURL.deletingLastPathComponent())
        }
        let window = WindowState(
            id: 1,
            processID: 42,
            bundleID: "dev.olly.perfbench.scratchpad",
            displayID: 1,
            tagMask: 1,
            frame: CGRect(x: 80, y: 80, width: 720, height: 480),
            title: "scratchpad",
            role: "AXWindow"
        )
        let parker = WindowParker(displayProvider: { displays() }) { _, _ in }
        try await registry.upsert(ScratchpadEntry(
            name: "terminal",
            bundleID: window.bundleID,
            role: window.role,
            lastVisibleFrame: WindowRecoveryFrame(window.frame)
        ))
        guard try await registry.matchingEntry(for: window) != nil else {
            return
        }
        let hidden = await parker.park(window)
        let hiddenEntry = try await registry.setVisibility(
            name: "terminal",
            isVisible: false,
            lastVisibleFrame: WindowRecoveryFrame(window.frame)
        )
        let visible = await parker.restore(window, targetFrame: hiddenEntry.lastVisibleFrame?.cgRect ?? window.frame)
        let visibleEntry = try await registry.setVisibility(name: "terminal", isVisible: true)
        sink.consume((hidden == nil ? 0 : 1) + (visible.reason == .show ? 1 : 0) + (visibleEntry.isVisible ? 1 : 0))
    }

    private func runPermissionRevokeRecovery() async throws {
        let stateURL = temporaryStateURL("permission-recovery.json")
        let journal = WindowRecoveryJournal(stateURL: stateURL)
        defer {
            try? FileManager.default.removeItem(at: stateURL.deletingLastPathComponent())
        }
        let parker = WindowParker(displayProvider: { displays() }) { _, _ in }
        var movedCount = 0
        for window in windows(count: options.windowCount, active: try Tag(index: 0), inactive: try Tag(index: 1)) {
            if let move = await parker.park(window) {
                try await journal.record(window: window, parkedFrame: move.targetFrame)
                movedCount += 1
            }
        }
        let state = try await journal.load()
        try await journal.remove(windowIDs: state.entries.map(\.windowID))
        sink.consume(movedCount + state.entries.count)
    }

    private func runSpaceDriftVerify() async throws {
        let store = WindowStore()
        let expected = NativeSpaceID(rawValue: 7)
        for window in windows(count: min(options.windowCount, 20), active: try Tag(index: 0), inactive: try Tag(index: 1)) {
            await store.upsert(window)
        }
        let invariant = NativeSpaceInvariant(
            windowStore: store,
            spaceProvider: AlternatingNativeSpaceProvider(expectedSpaceID: expected)
        )
        let result = await invariant.verify(expectedSpaceID: expected)
        sink.consume(result.issues.count + result.offSpaceWindowIDs.count)
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

    private func runThumbnailGeneration() async throws {
        let image = try PerfBenchImageFactory.thumbnail()
        let cache = WindowThumbnailCache(
            ttl: 0.25,
            capture: { _, _ in image },
            availability: { true }
        )
        for windowID in 1...20 {
            _ = try await cache.image(for: WindowID(windowID), size: CGSize(width: 240, height: 160))
        }
        sink.consume(await cache.cachedImage(for: 1) == nil ? 0 : 1)
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

    private func temporaryStateURL(_ fileName: String) -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent(fileName)
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

struct AlternatingNativeSpaceProvider: WindowNativeSpaceProviding {
    let expectedSpaceID: NativeSpaceID

    func nativeSpaceID(for window: WindowState) async -> NativeSpaceID? {
        Int(window.id).isMultiple(of: 5) ? NativeSpaceID(rawValue: expectedSpaceID.rawValue + 1) : expectedSpaceID
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
