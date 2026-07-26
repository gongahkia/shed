import CoreGraphics
import Darwin
import Foundation
import ollyKit

@main
enum SoakHarness {
    static func main() async throws {
        do {
            let options = try SoakOptions(arguments: Array(CommandLine.arguments.dropFirst()))
            let report = try await SoakRunner(options: options).run()
            try SoakReportWriter.write(report, to: options.outputPath)
            if !report.passed {
                FileHandle.standardError.write(Data("SoakHarness failed: RSS growth exceeded limit\n".utf8))
                exit(1)
            }
        } catch {
            FileHandle.standardError.write(Data("SoakHarness failed: \(error)\n".utf8))
            throw error
        }
    }
}

struct SoakOptions {
    var days = 7
    var eventsPerDay = 5_000
    var sampleIntervalSeconds = 600
    var maxGrowthPercent = 5.0
    var windowCount = 200
    var outputPath: String?

    init(arguments: [String]) throws {
        var index = 0
        while index < arguments.count {
            let argument = arguments[index]
            switch argument {
            case "--days":
                days = try Self.intValue(arguments, after: &index, name: argument)
            case "--events-per-day":
                eventsPerDay = try Self.intValue(arguments, after: &index, name: argument)
            case "--sample-interval-seconds":
                sampleIntervalSeconds = try Self.intValue(arguments, after: &index, name: argument)
            case "--max-growth-percent":
                maxGrowthPercent = try Self.doubleValue(arguments, after: &index, name: argument)
            case "--windows":
                windowCount = try Self.intValue(arguments, after: &index, name: argument)
            case "--output":
                outputPath = try Self.stringValue(arguments, after: &index, name: argument)
            case "--help", "-h":
                throw SoakError.help
            default:
                throw SoakError.unknownArgument(argument)
            }
            index += 1
        }
        days = max(1, days)
        eventsPerDay = max(1, eventsPerDay)
        sampleIntervalSeconds = max(1, sampleIntervalSeconds)
        maxGrowthPercent = max(0, maxGrowthPercent)
        windowCount = max(1, windowCount)
    }

    private static func intValue(_ arguments: [String], after index: inout Int, name: String) throws -> Int {
        let value = try stringValue(arguments, after: &index, name: name)
        guard let parsed = Int(value) else {
            throw SoakError.invalidValue(name, value)
        }
        return parsed
    }

    private static func doubleValue(_ arguments: [String], after index: inout Int, name: String) throws -> Double {
        let value = try stringValue(arguments, after: &index, name: name)
        guard let parsed = Double(value) else {
            throw SoakError.invalidValue(name, value)
        }
        return parsed
    }

    private static func stringValue(_ arguments: [String], after index: inout Int, name: String) throws -> String {
        index += 1
        guard arguments.indices.contains(index) else {
            throw SoakError.missingValue(name)
        }
        return arguments[index]
    }
}

enum SoakError: Error, CustomStringConvertible {
    case help
    case missingValue(String)
    case invalidValue(String, String)
    case unknownArgument(String)
    case rssSampleFailed(kern_return_t)

    var description: String {
        switch self {
        case .help:
            return """
            usage: SoakHarness [--days N] [--events-per-day N] [--sample-interval-seconds N] \
            [--max-growth-percent N] [--windows N] [--output path]
            """
        case let .missingValue(name):
            return "\(name) requires a value"
        case let .invalidValue(name, value):
            return "\(name) requires a numeric value, got \(value)"
        case let .unknownArgument(argument):
            return "unknown argument \(argument)"
        case let .rssSampleFailed(code):
            return "rss sample failed with kern_return_t \(code)"
        }
    }
}

struct SoakReport: Encodable {
    let generatedAt: String
    let days: Int
    let eventsPerDay: Int
    let totalEvents: Int
    let warmupEvents: Int
    let sampleIntervalSeconds: Int
    let maxGrowthPercent: Double
    let windowCount: Int
    let baselineRSSBytes: UInt64
    let peakRSSBytes: UInt64
    let growthPercent: Double
    let passed: Bool
    let samples: [RSSSample]
}

struct RSSSample: Encodable {
    let eventIndex: Int
    let virtualSecond: Int
    let rssBytes: UInt64
    let windowCount: Int
}

struct SoakRunner {
    let options: SoakOptions

    func run() async throws -> SoakReport {
        let store = WindowStore()
        let totalEvents = options.days * options.eventsPerDay
        let totalSeconds = options.days * 86_400
        let warmupEvents = options.windowCount
        var nextSampleSecond = 0

        for eventIndex in 1...warmupEvents {
            await applyEvent(eventIndex, to: store)
        }

        var samples = try [await sample(store: store, eventIndex: 0, virtualSecond: 0)]

        for eventIndex in 1...totalEvents {
            await applyEvent(warmupEvents + eventIndex, to: store)
            let virtualSecond = totalSeconds * eventIndex / totalEvents
            while virtualSecond >= nextSampleSecond + options.sampleIntervalSeconds {
                nextSampleSecond += options.sampleIntervalSeconds
                samples.append(try await sample(store: store, eventIndex: eventIndex, virtualSecond: nextSampleSecond))
            }
        }

        if samples.last?.eventIndex != totalEvents {
            samples.append(try await sample(store: store, eventIndex: totalEvents, virtualSecond: totalSeconds))
        }

        let baseline = samples.first?.rssBytes ?? 0
        let peak = samples.map(\.rssBytes).max() ?? baseline
        let growth = baseline == 0 ? 0 : Double(peak - baseline) / Double(baseline) * 100
        return SoakReport(
            generatedAt: ISO8601DateFormatter().string(from: Date()),
            days: options.days,
            eventsPerDay: options.eventsPerDay,
            totalEvents: totalEvents,
            warmupEvents: warmupEvents,
            sampleIntervalSeconds: options.sampleIntervalSeconds,
            maxGrowthPercent: options.maxGrowthPercent,
            windowCount: options.windowCount,
            baselineRSSBytes: baseline,
            peakRSSBytes: peak,
            growthPercent: growth,
            passed: growth <= options.maxGrowthPercent,
            samples: samples
        )
    }

    private func applyEvent(_ eventIndex: Int, to store: WindowStore) async {
        let windowID = WindowID(eventIndex % options.windowCount + 1)
        let displayID = DisplayID(eventIndex % 2 + 1)
        let tagMask = UInt64(1) << UInt64(eventIndex % 8)
        await store.upsert(
            WindowState(
                id: windowID,
                processID: pid_t(10_000 + eventIndex % 64),
                bundleID: "dev.olly.soak.\(eventIndex % 16)",
                displayID: displayID,
                tagMask: tagMask,
                isFloating: eventIndex.isMultiple(of: 13),
                frame: frame(for: eventIndex),
                title: "window-\(windowID)",
                role: "AXWindow",
                subrole: "AXStandardWindow"
            )
        )
        if eventIndex.isMultiple(of: 17) {
            _ = await store.windows(intersectingTagMask: tagMask)
        }
        if eventIndex.isMultiple(of: 29) {
            _ = await store.windows(onDisplay: displayID)
        }
        if eventIndex.isMultiple(of: 43) {
            _ = await store.remove(id: WindowID((eventIndex + options.windowCount / 2) % options.windowCount + 1))
        }
    }

    private func sample(store: WindowStore, eventIndex: Int, virtualSecond: Int) async throws -> RSSSample {
        RSSSample(
            eventIndex: eventIndex,
            virtualSecond: virtualSecond,
            rssBytes: try ResidentMemory.bytes(),
            windowCount: await store.count
        )
    }

    private func frame(for index: Int) -> CGRect {
        CGRect(
            x: CGFloat(index % 12) * 48,
            y: CGFloat(index % 9) * 36,
            width: CGFloat(480 + index % 240),
            height: CGFloat(320 + index % 180)
        )
    }
}

enum ResidentMemory {
    static func bytes() throws -> UInt64 {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) { pointer in
            pointer.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), rebound, &count)
            }
        }
        guard result == KERN_SUCCESS else {
            throw SoakError.rssSampleFailed(result)
        }
        return UInt64(info.resident_size)
    }
}

enum SoakReportWriter {
    static func write(_ report: SoakReport, to path: String?) throws {
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
