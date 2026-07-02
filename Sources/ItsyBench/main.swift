import AppKit
import ApplicationServices
import CoreVideo
import Darwin
import Dispatch
import Foundation
import ItsyEditor
import ItsyRender

private struct LatencyOptions {
	var pid: Int32
	var keyCode: CGKeyCode
	var displayID: CGDirectDisplayID
	var timeoutMS: Int
	var dirtyRects: Int
}

private struct MeasureOptions {
	var app: String
	var args: [String]
	var newInstance: Bool
	var staged: Bool
	var timeoutMS: Int
	var warmupPurge: Bool
}

private struct OpenOptions {
	var app: String
	var file: String
	var timeoutMS: Int
	var warmupPurge: Bool
}

private struct RopeOptions {
	var operations: Int
	var sliceLength: Int
}

private struct PieceTreeOptions {
	var operations: Int
	var sliceLength: Int
	var mmapFile: String?
	var requireMMapFile: Bool
}

private struct RenderHighlightCacheOptions {
	var frames: Int
	var lineCount: Int
}

private struct DisplayOptions {
	var displayID: CGDirectDisplayID
}

private struct DisplayResult: Encodable {
	var actual_refresh_hz: Double?
	var display_id: UInt32
	var mode_height: Int?
	var mode_pixel_height: Int?
	var mode_pixel_width: Int?
	var mode_refresh_hz: Double?
	var mode_width: Int?
	var nominal_refresh_hz: Double?
}

private struct LatencyResult: Encodable {
	var display_id: UInt32
	var dirty_rects: Int
	var event_to_keydown_ms: Double
	var key_code: UInt16
	var keydown_to_paint_ms: Double
	var latency_ms: Double
	var pid: Int32
}

private struct MeasureResult: Encodable {
	var app: String
	var first_window_visible_ms: Double
	var rss_kb: UInt64
	var stage_ms: [String: Double]?
	var startup_ms: Double
}

private struct OpenResult: Encodable {
	var app: String
	var file: String
	var open_first_page_visible_to_first_draw_ms: Double?
	var open_process_start_to_first_draw_ms: Double?
	var open_process_start_to_first_page_visible_ms: Double?
	var open_rss_kb: UInt64
	var stage_ms: [String: Double]
}

private struct RSSResult: Encodable {
	var pid: Int32
	var rss_kb: UInt64
}

private struct RopeBenchResult: Encodable {
	var final_length: Int
	var operations: Int
	var random_insert_ns_per_op: Double
	var sequential_insert_ns_per_op: Double
	var slice_length: Int
	var slice_ns_per_op: Double
	var slice_checksum: Int
}

private struct PieceTreeBenchResult: Encodable {
	var final_length: Int
	var mmap_load_bytes: Int?
	var mmap_load_line_count: Int?
	var mmap_load_ms: Double?
	var mmap_load_path: String?
	var operations: Int
	var random_insert_ns_per_op: Double
	var random_remove_ns_per_op: Double
	var sequential_insert_ns_per_op: Double
	var slice_length: Int
	var slice_ns_per_op: Double
	var slice_checksum: Int
}

private struct SmokeResult: Encodable {
	var mode: String
	var runs: Int
}

private struct ErrorResult: Encodable {
	var error: String
}

private enum BenchError: Error, CustomStringConvertible {
	case usage(String)
	case badPID(String)
	case launchTimeout
	case launchFailed(String)
	case axPermissionMissing
	case axObserverFailed(AXError)
	case axNotificationFailed(AXError)
	case appNotRunning(Int32)
	case displayStreamFailed
	case displayStreamStartFailed(CGError)
	case eventListenPermissionMissing
	case eventPostFailed
	case eventPostPermissionMissing
	case eventTapFailed
	case latencyTimeout
	case screenCapturePermissionMissing
	case windowTimeout
	case rssFailed(Int32, Int32)
	case purgeTimeout
	case purgeFailed(Int32)
	case encodeFailed
	case stageTimeout(String)

	var description: String {
		switch self {
		case let .usage(message):
			message
		case let .badPID(value):
			"invalid pid: \(value)"
		case .launchTimeout:
			"app launch timed out"
		case let .launchFailed(message):
			"app launch failed: \(message)"
		case .axPermissionMissing:
			"accessibility permission is required for measure"
		case let .axObserverFailed(error):
			"AXObserverCreate failed: \(error.rawValue)"
		case let .axNotificationFailed(error):
			"AXObserverAddNotification failed: \(error.rawValue)"
		case let .appNotRunning(pid):
			"no running app for pid \(pid)"
		case .displayStreamFailed:
			"CGDisplayStreamCreateWithDispatchQueue failed"
		case let .displayStreamStartFailed(error):
			"CGDisplayStreamStart failed: \(error.rawValue)"
		case .eventListenPermissionMissing:
			"input monitoring permission is required for latency"
		case .eventPostFailed:
			"failed to create keyboard event"
		case .eventPostPermissionMissing:
			"accessibility event-post permission is required for latency"
		case .eventTapFailed:
			"CGEventTapCreateForPid failed"
		case .latencyTimeout:
			"latency measurement timed out"
		case .screenCapturePermissionMissing:
			"screen capture permission is required for latency"
		case .windowTimeout:
			"window creation timed out"
		case let .rssFailed(pid, err):
			"proc_pid_rusage failed for pid \(pid): errno \(err)"
		case .purgeTimeout:
			"purge timed out"
		case let .purgeFailed(status):
			"purge failed with status \(status)"
		case .encodeFailed:
			"failed to encode JSON"
		case let .stageTimeout(name):
			"timed out waiting for stage: \(name)"
		}
	}
}

@main
enum ItsyBenchMain {
	static func main() {
		do {
			try run(Array(CommandLine.arguments.dropFirst()))
		} catch {
			printError(error)
			exit(1)
		}
	}

	private static func run(_ args: [String]) throws {
		if args.contains("--smoke") {
			try printJSON(SmokeResult(mode: "smoke", runs: parseRuns(args)))
			return
		}
		if args.isEmpty || args.first == "--help" || args.first == "-h" {
			print(usage)
			return
		}
		guard let command = args.first else {
			throw BenchError.usage(usage)
		}
		switch command {
		case "display":
			try printJSON(display(parseDisplay(Array(args.dropFirst()))))
		case "latency":
			try printJSON(latency(parseLatency(Array(args.dropFirst()))))
		case "measure":
			try printJSON(measure(parseMeasure(Array(args.dropFirst()))))
		case "open":
			try printJSON(open(parseOpen(Array(args.dropFirst()))))
		case "piecetree":
			try printJSON(pieceTree(parsePieceTree(Array(args.dropFirst()))))
		case "rope":
			try printJSON(rope(parseRope(Array(args.dropFirst()))))
		case "render-highlight-cache":
			let options = try parseRenderHighlightCache(Array(args.dropFirst()))
			try printJSON(runRenderHighlightCacheBenchmark(lineCount: options.lineCount, frames: options.frames))
		case "rss":
			let pid = try parsePID(Array(args.dropFirst()))
			try printJSON(RSSResult(pid: pid, rss_kb: residentSizeKB(pid: pid)))
		default:
			throw BenchError.usage("unknown command: \(command)")
		}
	}

	private static func parseRuns(_ args: [String]) -> Int {
		guard let index = args.firstIndex(of: "--runs") else {
			return 1
		}
		let valueIndex = args.index(after: index)
		guard valueIndex < args.endIndex, let runs = Int(args[valueIndex]) else {
			return 1
		}
		return runs
	}

	private static func parsePID(_ args: [String]) throws -> Int32 {
		guard let index = args.firstIndex(of: "--pid") else {
			throw BenchError.usage("usage: itsybench rss --pid <pid>")
		}
		let valueIndex = args.index(after: index)
		guard valueIndex < args.endIndex, let pid = Int32(args[valueIndex]) else {
			throw BenchError.badPID(valueIndex < args.endIndex ? args[valueIndex] : "")
		}
		return pid
	}

	private static func parseDisplay(_ args: [String]) throws -> DisplayOptions {
		var displayID = CGMainDisplayID()
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--display":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt32(args[valueIndex]) else {
					throw BenchError.usage("invalid --display")
				}
				displayID = CGDirectDisplayID(value)
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown display option: \(arg)")
			}
		}
		return DisplayOptions(displayID: displayID)
	}

	private static func parseLatency(_ args: [String]) throws -> LatencyOptions {
		var pid: Int32?
		var keyCode: CGKeyCode = 0
		var displayID = CGMainDisplayID()
		var timeoutMS = 2_000
		var dirtyRects = 1
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--pid":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int32(args[valueIndex]) else {
					throw BenchError.badPID(valueIndex < args.endIndex ? args[valueIndex] : "")
				}
				pid = value
				index = args.index(after: valueIndex)
			case "--key-code":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt16(args[valueIndex]) else {
					throw BenchError.usage("invalid --key-code")
				}
				keyCode = CGKeyCode(value)
				index = args.index(after: valueIndex)
			case "--display":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt32(args[valueIndex]) else {
					throw BenchError.usage("invalid --display")
				}
				displayID = CGDirectDisplayID(value)
				index = args.index(after: valueIndex)
			case "--timeout-ms":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --timeout-ms")
				}
				timeoutMS = value
				index = args.index(after: valueIndex)
			case "--dirty-rects":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --dirty-rects")
				}
				dirtyRects = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown latency option: \(arg)")
			}
		}
		guard let pid else {
			throw BenchError.usage("usage: itsybench latency --pid <pid> [--key-code <code>]")
		}
		return LatencyOptions(pid: pid, keyCode: keyCode, displayID: displayID, timeoutMS: timeoutMS, dirtyRects: dirtyRects)
	}

	private static func parseMeasure(_ args: [String]) throws -> MeasureOptions {
		var app: String?
		var appArgs: [String] = []
		var newInstance = false
		var staged = false
		var timeoutMS = 5_000
		var warmupPurge = false
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--app":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --app")
				}
				app = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--args":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --args")
				}
				appArgs.append(args[valueIndex])
				index = args.index(after: valueIndex)
			case "--new-instance":
				newInstance = true
				index = args.index(after: index)
			case "--staged":
				staged = true
				index = args.index(after: index)
			case "--timeout-ms":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --timeout-ms")
				}
				timeoutMS = value
				index = args.index(after: valueIndex)
			case "--warmup-purge":
				warmupPurge = true
				index = args.index(after: index)
			default:
				throw BenchError.usage("unknown measure option: \(arg)")
			}
		}
		guard let app else {
			throw BenchError.usage("usage: itsybench measure --app <path> [--args <arg>] [--new-instance] [--staged] [--timeout-ms <ms>] [--warmup-purge]")
		}
		return MeasureOptions(app: app, args: appArgs, newInstance: newInstance, staged: staged, timeoutMS: timeoutMS, warmupPurge: warmupPurge)
	}

	private static func parseOpen(_ args: [String]) throws -> OpenOptions {
		var app = defaultItsyAppPath()
		var file: String?
		var timeoutMS = 10_000
		var warmupPurge = false
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--app":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --app")
				}
				app = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--file":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --file")
				}
				file = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--timeout-ms":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --timeout-ms")
				}
				timeoutMS = value
				index = args.index(after: valueIndex)
			case "--warmup-purge":
				warmupPurge = true
				index = args.index(after: index)
			default:
				throw BenchError.usage("unknown open option: \(arg)")
			}
		}
		guard let file else {
			throw BenchError.usage("usage: itsybench open --file <path> [--app <path>] [--timeout-ms <ms>] [--warmup-purge]")
		}
		return OpenOptions(app: app, file: file, timeoutMS: timeoutMS, warmupPurge: warmupPurge)
	}

	private static func parseRope(_ args: [String]) throws -> RopeOptions {
		var operations = 1_000_000
		var sliceLength = 32
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--ops":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --ops")
				}
				operations = value
				index = args.index(after: valueIndex)
			case "--slice-length":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --slice-length")
				}
				sliceLength = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown rope option: \(arg)")
			}
		}
		return RopeOptions(operations: operations, sliceLength: sliceLength)
	}

	private static func parsePieceTree(_ args: [String]) throws -> PieceTreeOptions {
		var operations = 1_000_000
		var sliceLength = 32
		var mmapFile: String? = "bench/corpus/huge.log"
		var requireMMapFile = false
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--ops":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --ops")
				}
				operations = value
				index = args.index(after: valueIndex)
			case "--slice-length":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --slice-length")
				}
				sliceLength = value
				index = args.index(after: valueIndex)
			case "--file":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, !args[valueIndex].isEmpty else {
					throw BenchError.usage("missing value for --file")
				}
				mmapFile = args[valueIndex]
				requireMMapFile = true
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown piecetree option: \(arg)")
			}
		}
		return PieceTreeOptions(
			operations: operations,
			sliceLength: sliceLength,
			mmapFile: mmapFile,
			requireMMapFile: requireMMapFile
		)
	}

	private static func parseRenderHighlightCache(_ args: [String]) throws -> RenderHighlightCacheOptions {
		var frames = 60
		var lineCount = 100_000
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--frames":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --frames")
				}
				frames = value
				index = args.index(after: valueIndex)
			case "--lines":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --lines")
				}
				lineCount = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown render-highlight-cache option: \(arg)")
			}
		}
		return RenderHighlightCacheOptions(frames: frames, lineCount: lineCount)
	}

	private static func display(_ options: DisplayOptions) throws -> DisplayResult {
		let mode = CGDisplayCopyDisplayMode(options.displayID)
		let rates = displayLinkRates(for: options.displayID)
		return DisplayResult(
			actual_refresh_hz: rates.actual,
			display_id: options.displayID,
			mode_height: mode.map { $0.height },
			mode_pixel_height: mode.map { $0.pixelHeight },
			mode_pixel_width: mode.map { $0.pixelWidth },
			mode_refresh_hz: mode.map(\.refreshRate),
			mode_width: mode.map { $0.width },
			nominal_refresh_hz: rates.nominal
		)
	}

	private static func rope(_ options: RopeOptions) throws -> RopeBenchResult {
		let operations = options.operations
		let sliceLength = min(options.sliceLength, operations)
		var sequential = Rope()
		let sequentialNS = measureNanoseconds {
			for _ in 0 ..< operations {
				sequential.insert("a", at: sequential.length)
			}
		}
		var random = Rope()
		var insertRNG = BenchRNG(0xC0FFEE)
		let randomNS = measureNanoseconds {
			for _ in 0 ..< operations {
				random.insert("a", at: insertRNG.nextInt(random.length + 1))
			}
		}
		let sliceRope = Rope(String(repeating: "a", count: operations))
		var sliceRNG = BenchRNG(0xBAD5EED)
		var checksum = 0
		let sliceUpperBound = max(1, sliceRope.length - sliceLength + 1)
		let sliceNS = measureNanoseconds {
			for _ in 0 ..< operations {
				let start = sliceRNG.nextInt(sliceUpperBound)
				checksum += sliceRope.slice(start ..< start + sliceLength).utf8.count
			}
		}
		return RopeBenchResult(
			final_length: sequential.length + random.length,
			operations: operations,
			random_insert_ns_per_op: Double(randomNS) / Double(operations),
			sequential_insert_ns_per_op: Double(sequentialNS) / Double(operations),
			slice_length: sliceLength,
			slice_ns_per_op: Double(sliceNS) / Double(operations),
			slice_checksum: checksum
		)
	}

	private static func pieceTree(_ options: PieceTreeOptions) throws -> PieceTreeBenchResult {
		let operations = options.operations
		let sliceLength = min(options.sliceLength, operations)
		var sequential = PieceTree()
		let sequentialNS = measureNanoseconds {
			for _ in 0 ..< operations {
				sequential.insert("a", at: sequential.length)
			}
		}
		var random = PieceTree()
		var insertRNG = BenchRNG(0xC0FFEE)
		let randomNS = measureNanoseconds {
			for _ in 0 ..< operations {
				random.insert("a", at: insertRNG.nextInt(random.length + 1))
			}
		}
		var removing = PieceTree(bytes: [UInt8](repeating: 97, count: operations))
		var removeRNG = BenchRNG(0x51A7E)
		let removeNS = measureNanoseconds {
			for _ in 0 ..< operations {
				let offset = removeRNG.nextInt(removing.length)
				removing.remove(offset ..< offset + 1)
			}
		}
		let sliceTree = PieceTree(bytes: [UInt8](repeating: 97, count: operations))
		var sliceRNG = BenchRNG(0xBAD5EED)
		var checksum = 0
		let sliceUpperBound = max(1, sliceTree.length - sliceLength + 1)
		let sliceNS = measureNanoseconds {
			for _ in 0 ..< operations {
				let start = sliceRNG.nextInt(sliceUpperBound)
				checksum += sliceTree.substring(start ..< start + sliceLength).utf8.count
			}
		}
		var mmapPath: String?
		var mmapBytes: Int?
		var mmapLineCount: Int?
		var mmapLoadMS: Double?
		if let file = options.mmapFile {
			let url = URL(fileURLWithPath: file)
			if FileManager.default.fileExists(atPath: url.path) {
				mmapPath = url.path
				let loadNS = try measureNanoseconds {
					let mapped = try PieceTree(readingMappedFile: url)
					mmapBytes = mapped.length
					mmapLineCount = mapped.lineCount
				}
				mmapLoadMS = Double(loadNS) / 1_000_000
			} else if options.requireMMapFile {
				throw BenchError.usage("missing piecetree mmap file: \(file)")
			}
		}
		return PieceTreeBenchResult(
			final_length: sequential.length + random.length + removing.length,
			mmap_load_bytes: mmapBytes,
			mmap_load_line_count: mmapLineCount,
			mmap_load_ms: mmapLoadMS,
			mmap_load_path: mmapPath,
			operations: operations,
			random_insert_ns_per_op: Double(randomNS) / Double(operations),
			random_remove_ns_per_op: Double(removeNS) / Double(operations),
			sequential_insert_ns_per_op: Double(sequentialNS) / Double(operations),
			slice_length: sliceLength,
			slice_ns_per_op: Double(sliceNS) / Double(operations),
			slice_checksum: checksum
		)
	}

	private static func open(_ options: OpenOptions) throws -> OpenResult {
		if options.warmupPurge {
			try purgeMemory()
		}
		let appURL = URL(fileURLWithPath: options.app)
		let fileURL = URL(fileURLWithPath: options.file)
		let stageURL = temporaryStageURL()
		FileManager.default.createFile(atPath: stageURL.path, contents: nil)
		let start = DispatchTime.now().uptimeNanoseconds
		let deadline = Date(timeIntervalSinceNow: Double(options.timeoutMS) / 1000)
		let process = Process()
		process.executableURL = appURL
		process.arguments = [fileURL.path]
		process.environment = ProcessInfo.processInfo.environment.merging(["ITSY_BENCH_STAGES_PATH": stageURL.path]) { _, new in new }
		try process.run()
		defer {
			terminate(process)
			try? FileManager.default.removeItem(at: stageURL)
		}
		guard waitForStage("first_draw", at: stageURL, deadline: deadline) else {
			throw BenchError.stageTimeout("first_draw")
		}
		let rawStages = loadRawStages(from: stageURL)
		let stageMS = loadStages(from: stageURL, since: start)
		return OpenResult(
			app: appURL.lastPathComponent,
			file: fileURL.path,
			open_first_page_visible_to_first_draw_ms: stageDelta("first_page_visible", "first_draw", in: rawStages),
			open_process_start_to_first_draw_ms: stageDelta("process_start", "first_draw", in: rawStages),
			open_process_start_to_first_page_visible_ms: stageDelta("process_start", "first_page_visible", in: rawStages),
			open_rss_kb: try residentSizeKB(pid: process.processIdentifier),
			stage_ms: stageMS
		)
	}

	private static func latency(_ options: LatencyOptions) throws -> LatencyResult {
		guard CGPreflightListenEventAccess() else {
			throw BenchError.eventListenPermissionMissing
		}
		guard CGPreflightPostEventAccess() else {
			throw BenchError.eventPostPermissionMissing
		}
		guard CGPreflightScreenCaptureAccess() else {
			throw BenchError.screenCapturePermissionMissing
		}
		guard let app = NSRunningApplication(processIdentifier: options.pid) else {
			throw BenchError.appNotRunning(options.pid)
		}
		app.activate(options: [.activateIgnoringOtherApps])
		let deadline = Date(timeIntervalSinceNow: Double(options.timeoutMS) / 1000)
		let probe = LatencyProbe(requiredDirtyRects: options.dirtyRects)
		let tap = try makeEventTap(pid: options.pid, probe: probe)
		let tapSource = CFMachPortCreateRunLoopSource(nil, tap, 0)
		CFRunLoopAddSource(CFRunLoopGetCurrent(), tapSource, .defaultMode)
		defer {
			CFRunLoopRemoveSource(CFRunLoopGetCurrent(), tapSource, .defaultMode)
			CFMachPortInvalidate(tap)
		}
		let stream = try makeDisplayStream(displayID: options.displayID, probe: probe)
		defer { stream.stop() }
		try wait(until: { probe.hasBaseline }, deadline: deadline)
		Thread.sleep(forTimeInterval: 0.05)
		let postedAt = DispatchTime.now().uptimeNanoseconds
		probe.markPosted(postedAt)
		try postKey(pid: options.pid, keyCode: options.keyCode)
		try wait(until: { probe.keyDownTimestamp != nil }, deadline: deadline)
		try wait(until: { probe.paintTimestamp != nil }, deadline: deadline)
		guard let keyDownAt = probe.keyDownTimestamp, let paintAt = probe.paintTimestamp else {
			throw BenchError.latencyTimeout
		}
		let eventToKeyDown = Double(keyDownAt - postedAt) / 1_000_000
		let keyDownToPaint = Double(paintAt - keyDownAt) / 1_000_000
		return LatencyResult(
			display_id: options.displayID,
			dirty_rects: probe.paintDirtyRects,
			event_to_keydown_ms: eventToKeyDown,
			key_code: options.keyCode,
			keydown_to_paint_ms: keyDownToPaint,
			latency_ms: keyDownToPaint,
			pid: options.pid
		)
	}

	private static func measure(_ options: MeasureOptions) throws -> MeasureResult {
		if options.warmupPurge {
			try purgeMemory()
		}
		try requireAccessibility()
		let url = URL(fileURLWithPath: options.app)
		let start = DispatchTime.now().uptimeNanoseconds
		let deadline = Date(timeIntervalSinceNow: Double(options.timeoutMS) / 1000)
		let stageURL = options.staged ? temporaryStageURL() : nil
		if let stageURL {
			FileManager.default.createFile(atPath: stageURL.path, contents: nil)
		}
		let environment = stageURL.map { ["ITSY_BENCH_STAGES_PATH": $0.path] } ?? [:]
		let app = try launch(url: url, args: options.args, newInstance: options.newInstance, environment: environment, deadline: deadline)
		defer { terminate(app) }
		let firstWindow = try waitForFirstWindow(pid: app.processIdentifier, start: start, deadline: deadline)
		if let stageURL {
			_ = waitForStage("first_draw", at: stageURL, deadline: deadline)
		}
		let startup = Double(firstWindow - start) / 1_000_000
		return MeasureResult(
			app: url.lastPathComponent,
			first_window_visible_ms: startup,
			rss_kb: try residentSizeKB(pid: app.processIdentifier),
			stage_ms: stageURL.map { loadStages(from: $0, since: start) },
			startup_ms: startup
		)
	}

	private static func terminate(_ app: NSRunningApplication) {
		guard !app.isTerminated else {
			return
		}
		app.terminate()
		let deadline = Date(timeIntervalSinceNow: 2)
		while !app.isTerminated, Date() < deadline {
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.02))
		}
		if !app.isTerminated {
			app.forceTerminate()
			let forceDeadline = Date(timeIntervalSinceNow: 1)
			while !app.isTerminated, Date() < forceDeadline {
				RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.02))
			}
		}
		Thread.sleep(forTimeInterval: 0.05)
	}

	private static func terminate(_ process: Process) {
		guard process.isRunning else {
			return
		}
		process.terminate()
		let deadline = Date(timeIntervalSinceNow: 2)
		while process.isRunning, Date() < deadline {
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.02))
		}
		if process.isRunning {
			kill(process.processIdentifier, SIGKILL)
			process.waitUntilExit()
		}
	}

	private static func launch(url: URL, args: [String], newInstance: Bool, environment: [String: String], deadline: Date) throws -> NSRunningApplication {
		let config = NSWorkspace.OpenConfiguration()
		config.arguments = args
		config.activates = false
		config.addsToRecentItems = false
		config.createsNewApplicationInstance = newInstance
		if !environment.isEmpty {
			config.environment = ProcessInfo.processInfo.environment.merging(environment) { _, new in new }
		}
		let semaphore = DispatchSemaphore(value: 0)
		var runningApp: NSRunningApplication?
		var launchError: Error?
		NSWorkspace.shared.openApplication(at: url, configuration: config) { app, error in
			runningApp = app
			launchError = error
			semaphore.signal()
		}
		guard semaphore.wait(timeout: dispatchDeadline(deadline)) == .success else {
			throw BenchError.launchTimeout
		}
		if let launchError {
			throw BenchError.launchFailed(launchError.localizedDescription)
		}
		guard let runningApp else {
			throw BenchError.launchFailed("no running app returned")
		}
		return runningApp
	}

	private static func temporaryStageURL() -> URL {
		FileManager.default.temporaryDirectory.appendingPathComponent("itsy-bench-stages-\(UUID().uuidString).log")
	}

	private static func waitForStage(_ name: String, at url: URL, deadline: Date) -> Bool {
		while Date() < deadline {
			if loadRawStages(from: url)[name] != nil {
				return true
			}
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.01))
		}
		return false
	}

	private static func loadStages(from url: URL, since start: UInt64) -> [String: Double] {
		loadRawStages(from: url).mapValues { timestamp in
			Double(timestamp - min(timestamp, start)) / 1_000_000
		}
	}

	private static func loadRawStages(from url: URL) -> [String: UInt64] {
		guard let text = try? String(contentsOf: url, encoding: .utf8) else {
			return [:]
		}
		var stages: [String: UInt64] = [:]
		for line in text.split(separator: "\n") {
			let parts = line.split(separator: " ")
			guard parts.count == 2, let timestamp = UInt64(parts[1]) else {
				continue
			}
			stages[String(parts[0])] = timestamp
		}
		return stages
	}

	private static func stageDelta(_ lower: String, _ upper: String, in stages: [String: UInt64]) -> Double? {
		guard let lowerValue = stages[lower], let upperValue = stages[upper], upperValue >= lowerValue else {
			return nil
		}
		return Double(upperValue - lowerValue) / 1_000_000
	}

	private static func waitForFirstWindow(pid: pid_t, start: UInt64, deadline: Date) throws -> UInt64 {
		let element = AXUIElementCreateApplication(pid)
		let waiter = WindowWaiter()
		var observer: AXObserver?
		var notificationAdded = false
		if AXObserverCreate(pid, axCallback, &observer) == .success, let observer {
			let source = AXObserverGetRunLoopSource(observer)
			CFRunLoopAddSource(CFRunLoopGetCurrent(), source, .defaultMode)
			let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(waiter).toOpaque())
			if AXObserverAddNotification(observer, element, kAXWindowCreatedNotification as CFString, refcon) == .success {
				notificationAdded = true
			}
			defer {
				if notificationAdded {
					AXObserverRemoveNotification(observer, element, kAXWindowCreatedNotification as CFString)
				}
				CFRunLoopRemoveSource(CFRunLoopGetCurrent(), source, .defaultMode)
			}
			return try waitForFirstWindowValue(element: element, waiter: waiter, start: start, deadline: deadline)
		}
		return try waitForFirstWindowValue(element: element, waiter: waiter, start: start, deadline: deadline)
	}

	private static func waitForFirstWindowValue(element: AXUIElement, waiter: WindowWaiter, start: UInt64, deadline: Date) throws -> UInt64 {
		while Date() < deadline {
			if hasWindow(element) {
				return max(DispatchTime.now().uptimeNanoseconds, start)
			}
			if let timestamp = waiter.timestamp {
				return max(timestamp, start)
			}
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.01))
		}
		throw BenchError.windowTimeout
	}

	private static func dispatchDeadline(_ deadline: Date) -> DispatchTime {
		let remaining = max(0, deadline.timeIntervalSinceNow)
		return .now() + .milliseconds(Int(remaining * 1000))
	}

	private static func requireAccessibility() throws {
		let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: false] as CFDictionary
		guard AXIsProcessTrustedWithOptions(opts) else {
			throw BenchError.axPermissionMissing
		}
	}

	private static func hasWindow(_ element: AXUIElement) -> Bool {
		var value: CFTypeRef?
		let error = AXUIElementCopyAttributeValue(element, kAXWindowsAttribute as CFString, &value)
		guard error == .success, let windows = value as? [AXUIElement] else {
			return false
		}
		return !windows.isEmpty
	}

	private static func makeEventTap(pid: Int32, probe: LatencyProbe) throws -> CFMachPort {
		let mask = CGEventMask(1) << CGEventType.keyDown.rawValue
		let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(probe).toOpaque())
		guard let tap = CGEvent.tapCreateForPid(
			pid: pid,
			place: .headInsertEventTap,
			options: .listenOnly,
			eventsOfInterest: mask,
			callback: latencyEventTapCallback,
			userInfo: refcon
		) else {
			throw BenchError.eventTapFailed
		}
		CGEvent.tapEnable(tap: tap, enable: true)
		return tap
	}

	private static func makeDisplayStream(displayID: CGDirectDisplayID, probe: LatencyProbe) throws -> CGDisplayStream {
		let width = CGDisplayPixelsWide(displayID)
		let height = CGDisplayPixelsHigh(displayID)
		let queue = DispatchQueue(label: "dev.itsy.itsybench.latency.display")
		guard let stream = CGDisplayStream(
			dispatchQueueDisplay: displayID,
			outputWidth: width,
			outputHeight: height,
			pixelFormat: Int32(kCVPixelFormatType_32BGRA),
			properties: nil,
			queue: queue,
			handler: { status, _, _, update in
				probe.handleFrame(status: status, update: update)
			}
		) else {
			throw BenchError.displayStreamFailed
		}
		let error = stream.start()
		guard error == .success else {
			throw BenchError.displayStreamStartFailed(error)
		}
		return stream
	}

	private static func displayLinkRates(for displayID: CGDirectDisplayID) -> (actual: Double?, nominal: Double?) {
		var link: CVDisplayLink?
		guard CVDisplayLinkCreateWithCGDisplay(displayID, &link) == kCVReturnSuccess, let link else {
			return (nil, nil)
		}
		let actualPeriod = CVDisplayLinkGetActualOutputVideoRefreshPeriod(link)
		let actual = actualPeriod > 0 ? 1 / actualPeriod : nil
		let nominalPeriod = CVDisplayLinkGetNominalOutputVideoRefreshPeriod(link)
		let nominal = nominalPeriod.timeValue > 0 && nominalPeriod.timeScale > 0
			? Double(nominalPeriod.timeScale) / Double(nominalPeriod.timeValue)
			: nil
		return (actual, nominal)
	}

	private static func postKey(pid: Int32, keyCode: CGKeyCode) throws {
		let source = CGEventSource(stateID: .hidSystemState)
		guard let down = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: true),
		      let up = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: false) else {
			throw BenchError.eventPostFailed
		}
		down.post(tap: .cghidEventTap)
		up.post(tap: .cghidEventTap)
	}

	private static func wait(until condition: () -> Bool, deadline: Date) throws {
		while !condition(), Date() < deadline {
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.005))
		}
		guard condition() else {
			throw BenchError.latencyTimeout
		}
	}

	private static func residentSizeKB(pid: Int32) throws -> UInt64 {
		var info = rusage_info_v2()
		let result = withUnsafeMutablePointer(to: &info) { pointer in
			pointer.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) {
				proc_pid_rusage(pid, RUSAGE_INFO_V2, $0)
			}
		}
		guard result == 0 else {
			throw BenchError.rssFailed(pid, errno)
		}
		return UInt64(info.ri_resident_size / 1024)
	}

	private static func defaultItsyAppPath() -> String {
		URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
			.appendingPathComponent(".build/release/ItsyApp")
			.path
	}

	private static func purgeMemory() throws {
		let url = URL(fileURLWithPath: "/usr/bin/purge")
		guard FileManager.default.isExecutableFile(atPath: url.path) else {
			return
		}
		let process = Process()
		process.executableURL = url
		try process.run()
		let deadline = Date(timeIntervalSinceNow: 2)
		while process.isRunning, Date() < deadline {
			Thread.sleep(forTimeInterval: 0.05)
		}
		guard !process.isRunning else {
			process.terminate()
			throw BenchError.purgeTimeout
		}
		guard process.terminationStatus == 0 else {
			throw BenchError.purgeFailed(process.terminationStatus)
		}
	}

	private static func printJSON<T: Encodable>(_ value: T) throws {
		let encoder = JSONEncoder()
		encoder.outputFormatting = [.sortedKeys]
		guard let string = String(data: try encoder.encode(value), encoding: .utf8) else {
			throw BenchError.encodeFailed
		}
		print(string)
	}

	private static func printError(_ error: Error) {
		let message = (error as? BenchError)?.description ?? String(describing: error)
		let result = ErrorResult(error: message)
		if let data = try? JSONEncoder().encode(result), let string = String(data: data, encoding: .utf8) {
			print(string)
		} else {
			print(#"{"error":"unknown"}"#)
		}
	}

	private static let usage = """
	usage:
	  itsybench display [--display <id>]
	  itsybench measure --app <path> [--args <arg>] [--new-instance] [--staged] [--timeout-ms <ms>] [--warmup-purge]
	  itsybench open --file <path> [--app <path>] [--timeout-ms <ms>] [--warmup-purge]
	  itsybench piecetree [--ops <count>] [--slice-length <bytes>] [--file <path>]
	  itsybench render-highlight-cache [--lines <count>] [--frames <count>]
	  itsybench rope [--ops <count>] [--slice-length <bytes>]
	  itsybench rss --pid <pid>
	  itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
	"""
}

private func measureNanoseconds(_ body: () throws -> Void) rethrows -> UInt64 {
	let start = DispatchTime.now().uptimeNanoseconds
	try body()
	return DispatchTime.now().uptimeNanoseconds - start
}

private struct BenchRNG {
	private var state: UInt64

	init(_ seed: UInt64) {
		state = seed
	}

	mutating func nextInt(_ upperBound: Int) -> Int {
		precondition(upperBound > 0)
		state = state &* 2862933555777941757 &+ 3037000493
		return Int(state % UInt64(upperBound))
	}
}

private final class WindowWaiter {
	private let lock = NSLock()
	private var value: UInt64?

	var hasValue: Bool {
		lock.lock()
		defer { lock.unlock() }
		return value != nil
	}

	var timestamp: UInt64? {
		lock.lock()
		defer { lock.unlock() }
		return value
	}

	func mark(_ timestamp: UInt64) {
		lock.lock()
		defer { lock.unlock() }
		guard value == nil else {
			return
		}
		value = timestamp
	}
}

private final class LatencyProbe {
	private let lock = NSLock()
	private let requiredDirtyRects: Int
	private var baselineReady = false
	private var postedAt: UInt64?
	private var keyDownAt: UInt64?
	private var paintAt: UInt64?
	private var dirtyRectsAtPaint = 0

	init(requiredDirtyRects: Int) {
		self.requiredDirtyRects = requiredDirtyRects
	}

	var hasBaseline: Bool {
		lock.lock()
		defer { lock.unlock() }
		return baselineReady
	}

	var keyDownTimestamp: UInt64? {
		lock.lock()
		defer { lock.unlock() }
		return keyDownAt
	}

	var paintTimestamp: UInt64? {
		lock.lock()
		defer { lock.unlock() }
		return paintAt
	}

	var paintDirtyRects: Int {
		lock.lock()
		defer { lock.unlock() }
		return dirtyRectsAtPaint
	}

	func markPosted(_ timestamp: UInt64) {
		lock.lock()
		defer { lock.unlock() }
		postedAt = timestamp
	}

	func markKeyDown(_ timestamp: UInt64) {
		lock.lock()
		defer { lock.unlock() }
		guard keyDownAt == nil else {
			return
		}
		keyDownAt = timestamp
	}

	func handleFrame(status: CGDisplayStreamFrameStatus, update: CGDisplayStreamUpdate?) {
		guard status == .frameComplete else {
			return
		}
		var count = 0
		if let update {
			var rectCount = 0
			_ = update.getRects(.dirtyRects, rectCount: &rectCount)
			count = rectCount
		}
		lock.lock()
		defer { lock.unlock() }
		guard baselineReady else {
			baselineReady = true
			return
		}
		guard postedAt != nil, keyDownAt != nil, paintAt == nil, count >= requiredDirtyRects else {
			return
		}
		paintAt = DispatchTime.now().uptimeNanoseconds
		dirtyRectsAtPaint = count
	}
}

private let axCallback: AXObserverCallback = { _, _, notification, refcon in
	guard notification as String == kAXWindowCreatedNotification, let refcon else {
		return
	}
	let waiter = Unmanaged<WindowWaiter>.fromOpaque(refcon).takeUnretainedValue()
	waiter.mark(DispatchTime.now().uptimeNanoseconds)
}

private let latencyEventTapCallback: CGEventTapCallBack = { _, type, event, refcon in
	if type == .keyDown, let refcon {
		let probe = Unmanaged<LatencyProbe>.fromOpaque(refcon).takeUnretainedValue()
		probe.markKeyDown(DispatchTime.now().uptimeNanoseconds)
	}
	return Unmanaged.passUnretained(event)
}
