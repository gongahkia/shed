import AppKit
import ApplicationServices
import CoreServices
import CoreVideo
import Darwin
import Dispatch
import Foundation
import ItsyEditor
import ItsyRender
import ItsySyntax

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
	var runs: Int
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
	var mmapContract: Bool
	var mmapRSSBudgetKB: UInt64
}

private struct UndoOptions {
	var operations: Int
	var bufferBytes: Int
	var rssBudgetKB: UInt64
}

private struct RenderHighlightCacheOptions {
	var frames: Int
	var lineCount: Int
}

private struct WorkspaceFSEventsOptions {
	var timeoutMS: Int
}

private struct WorkspaceIndexOptions {
	var files: Int
	var ignoredFiles: Int
}

private struct WorkflowOptions {
	var file: String
	var repeats: Int
	var paneTransitions: Int
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

private struct TraceReportOptions {
	var tracePath: String
	var scenario: String?
	var state: String?
	var fixtureChecksum: String?
	var appCommit: String?
	var displayID: CGDirectDisplayID
}

private struct TraceMetric: Encodable {
	var max_ms: Double
	var median_ms: Double
	var p95_ms: Double
	var raw_samples_ms: [Double]
	var sample_count: Int
}

private struct TraceReportMetadata: Encodable {
	var app_commit: String?
	var display: DisplayResult
	var fixture_checksum: String?
	var hardware: String?
	var macos: String
	var state: String?
}

private struct TraceReportResult: Encodable {
	var event_count: Int
	var failure_reason: String?
	var metadata: TraceReportMetadata
	var metrics: [String: TraceMetric]
	var scenario: String?
	var trace_path: String
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

private struct MeasureSampleResult: Encodable {
	var app: String
	var app_did_finish_launching_to_first_window_visible_ms: Double?
	var external_first_window_visible_ms: Double
	var first_window_visible_ms: Double
	var process_start_to_first_window_visible_ms: Double?
	var rss_kb: UInt64
	var stage_ms: [String: Double]?
	var startup_ms: Double
}

private struct MeasureResult: Encodable {
	var app: String
	var app_did_finish_launching_to_first_window_visible_ms: Double?
	var external_first_window_visible_ms: Double
	var external_first_window_visible_max_ms: Double?
	var external_first_window_visible_min_ms: Double?
	var first_window_visible_max_ms: Double?
	var first_window_visible_min_ms: Double?
	var first_window_visible_ms: Double
	var process_start_to_first_window_visible_ms: Double?
	var rss_kb: UInt64
	var runs: Int
	var samples: [MeasureSampleResult]?
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
	var mmap_contract_passed: Bool?
	var mmap_edit_length: Int?
	var mmap_peak_rss_delta_kb: UInt64?
	var mmap_rss_budget_kb: UInt64?
	var mmap_save_bytes: Int?
	var mmap_search_offset: Int?
	var operations: Int
	var random_insert_ns_per_op: Double
	var random_remove_ns_per_op: Double
	var sequential_insert_ns_per_op: Double
	var slice_length: Int
	var slice_ns_per_op: Double
	var slice_checksum: Int
}

private struct MMapContractResult {
	var editLength: Int
	var passed: Bool
	var peakRSSDeltaKB: UInt64
	var saveBytes: Int
	var searchOffset: Int?
}

private struct UndoBenchResult: Encodable {
	var after_record_rss_delta_kb: UInt64
	var after_record_rss_kb: UInt64
	var after_redo_rss_delta_kb: UInt64
	var after_redo_rss_kb: UInt64
	var after_undo_rss_delta_kb: UInt64
	var after_undo_rss_kb: UInt64
	var baseline_rss_kb: UInt64
	var buffer_bytes: Int
	var final_checksum: Int
	var final_length: Int
	var final_undo_entries: Int
	var max_rss_delta_kb: UInt64
	var operations: Int
	var record_ns_per_op: Double
	var retained_undo_entries: Int
	var rss_budget_kb: UInt64
	var rss_budget_passed: Bool
	var sampled_peak_rss_kb: UInt64
	var redo_ns_per_op: Double
	var undo_ns_per_op: Double
}

private struct SmokeResult: Encodable {
	var highlight_span_counts: [String: Int]
	var mode: String
	var runs: Int
}

private struct WorkspaceFSEventsResult: Encodable {
	var batches: Int
	var create_seen: Bool
	var modify_seen: Bool
	var remove_seen: Bool
	var rename_seen: Bool
	var resume_seen: Bool
	var stored_event_id: UInt64?
}

private struct WorkspaceIndexBenchResult: Encodable {
	var build_ms: Double
	var files_requested: Int
	var first_symbol_query_ms: Double
	var first_symbol_query_under_100ms: Bool
	var ignored_files_requested: Int
	var ignored_indexed_files: Int
	var indexed_files: Int
	var load_ms: Double
	var query_results: Int
	var save_ms: Double
}

private struct WorkflowSample: Encodable {
	var edit_ms: Double
	var open_ms: Double
	var pane_transition_ms: Double
	var rss_delta_kb: UInt64
	var save_ms: Double
	var search_ms: Double
}

private struct WorkflowBenchResult: Encodable {
	var corpus_bytes: Int
	var corpus_path: String
	var owner: String
	var repeats: Int
	var samples: [WorkflowSample]
	var variance_ms2: [String: Double]
	var means_ms: [String: Double]
}

private struct HighlightSmokeSample {
	var filename: String
	var source: String
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
	case smokeFailed(String)
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
		case let .smokeFailed(message):
			"smoke failed: \(message)"
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

private final class OpenApplicationResultBox: @unchecked Sendable {
	private let lock = NSLock()
	private var app: NSRunningApplication?
	private var error: Error?

	func store(app: NSRunningApplication?, error: Error?) {
		lock.lock()
		self.app = app
		self.error = error
		lock.unlock()
	}

	func snapshot() -> (NSRunningApplication?, Error?) {
		lock.lock()
		defer { lock.unlock() }
		return (app, error)
	}
}

@main
@MainActor
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
			try printJSON(smoke(runs: parseRuns(args)))
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
		case "trace-report":
			try printJSON(traceReport(parseTraceReport(Array(args.dropFirst()))))
		case "undo":
			try printJSON(undo(parseUndo(Array(args.dropFirst()))))
		case "workspace-fsevents":
			try printJSON(workspaceFSEvents(parseWorkspaceFSEvents(Array(args.dropFirst()))))
		case "workspace-index":
			try printJSON(workspaceIndex(parseWorkspaceIndex(Array(args.dropFirst()))))
		case "workflow":
			try printJSON(workflow(parseWorkflow(Array(args.dropFirst()))))
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

	private static func parseTraceReport(_ args: [String]) throws -> TraceReportOptions {
		var tracePath: String?
		var scenario: String?
		var state: String?
		var fixtureChecksum: String?
		var appCommit: String?
		var displayID = CGMainDisplayID()
		var index = args.startIndex
		while index < args.endIndex {
			switch args[index] {
			case "--trace":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, !args[valueIndex].isEmpty else {
					throw BenchError.usage("missing value for --trace")
				}
				tracePath = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--scenario":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --scenario")
				}
				scenario = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--state":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, ["cold", "warm"].contains(args[valueIndex]) else {
					throw BenchError.usage("--state must be cold or warm")
				}
				state = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--fixture-checksum":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --fixture-checksum")
				}
				fixtureChecksum = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--app-commit":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex else {
					throw BenchError.usage("missing value for --app-commit")
				}
				appCommit = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--display":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt32(args[valueIndex]) else {
					throw BenchError.usage("invalid --display")
				}
				displayID = CGDirectDisplayID(value)
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown trace-report option: \(args[index])")
			}
		}
		guard let tracePath else {
			throw BenchError.usage("usage: itsybench trace-report --trace <jsonl> [--scenario <name>]")
		}
		return TraceReportOptions(
			tracePath: tracePath,
			scenario: scenario,
			state: state,
			fixtureChecksum: fixtureChecksum,
			appCommit: appCommit,
			displayID: displayID
		)
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
		var runs = 1
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
			case "--runs":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --runs")
				}
				runs = value
				index = args.index(after: valueIndex)
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
			throw BenchError.usage("usage: itsybench measure --app <path> [--args <arg>] [--new-instance] [--runs <count>] [--staged] [--timeout-ms <ms>] [--warmup-purge]")
		}
		return MeasureOptions(
			app: app,
			args: appArgs,
			newInstance: newInstance,
			runs: runs,
			staged: staged,
			timeoutMS: timeoutMS,
			warmupPurge: warmupPurge
		)
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
		var mmapContract = false
		var mmapRSSBudgetKB: UInt64 = 1_572_864
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
			case "--mmap-contract":
				mmapContract = true
				index = args.index(after: index)
			case "--mmap-rss-budget-kb":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt64(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --mmap-rss-budget-kb")
				}
				mmapRSSBudgetKB = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown piecetree option: \(arg)")
			}
		}
		return PieceTreeOptions(
			operations: operations,
			sliceLength: sliceLength,
			mmapFile: mmapFile,
			requireMMapFile: requireMMapFile,
			mmapContract: mmapContract,
			mmapRSSBudgetKB: mmapRSSBudgetKB
		)
	}

	private static func parseWorkflow(_ args: [String]) throws -> WorkflowOptions {
		var file: String?
		var repeats = 5
		var paneTransitions = 200
		var index = args.startIndex
		while index < args.endIndex {
			switch args[index] {
			case "--file":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, !args[valueIndex].isEmpty else {
					throw BenchError.usage("missing value for --file")
				}
				file = args[valueIndex]
				index = args.index(after: valueIndex)
			case "--repeats":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --repeats")
				}
				repeats = value
				index = args.index(after: valueIndex)
			case "--pane-transitions":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --pane-transitions")
				}
				paneTransitions = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown workflow option: \(args[index])")
			}
		}
		guard let file else {
			throw BenchError.usage("usage: itsybench workflow --file <path> [--repeats <count>] [--pane-transitions <count>]")
		}
		return WorkflowOptions(file: file, repeats: repeats, paneTransitions: paneTransitions)
	}

	private static func parseUndo(_ args: [String]) throws -> UndoOptions {
		var operations = 100_000
		var bufferBytes = 10 * 1024 * 1024
		var rssBudgetKB: UInt64 = 100 * 1024
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
			case "--buffer-bytes":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --buffer-bytes")
				}
				bufferBytes = value
				index = args.index(after: valueIndex)
			case "--rss-budget-kb":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = UInt64(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --rss-budget-kb")
				}
				rssBudgetKB = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown undo option: \(arg)")
			}
		}
		return UndoOptions(operations: operations, bufferBytes: bufferBytes, rssBudgetKB: rssBudgetKB)
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

	private static func parseWorkspaceFSEvents(_ args: [String]) throws -> WorkspaceFSEventsOptions {
		var timeoutMS = 5_000
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--timeout-ms":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --timeout-ms")
				}
				timeoutMS = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown workspace-fsevents option: \(arg)")
			}
		}
		return WorkspaceFSEventsOptions(timeoutMS: timeoutMS)
	}

	private static func parseWorkspaceIndex(_ args: [String]) throws -> WorkspaceIndexOptions {
		var files = 10_000
		var ignoredFiles = 2_000
		var index = args.startIndex
		while index < args.endIndex {
			let arg = args[index]
			switch arg {
			case "--files":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value > 0 else {
					throw BenchError.usage("invalid --files")
				}
				files = value
				index = args.index(after: valueIndex)
			case "--ignored-files":
				let valueIndex = args.index(after: index)
				guard valueIndex < args.endIndex, let value = Int(args[valueIndex]), value >= 0 else {
					throw BenchError.usage("invalid --ignored-files")
				}
				ignoredFiles = value
				index = args.index(after: valueIndex)
			default:
				throw BenchError.usage("unknown workspace-index option: \(arg)")
			}
		}
		return WorkspaceIndexOptions(files: files, ignoredFiles: ignoredFiles)
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

	private static func traceReport(_ options: TraceReportOptions) throws -> TraceReportResult {
		let events = loadTraceEvents(path: options.tracePath)
		let scenarioEvents = events.filter { $0.name == "scenario.complete" }
		let scenarioFailure = scenarioEvents.last(where: { $0.attributes["outcome"] == "failure" })
		var metrics: [String: TraceMetric] = [:]
		var failures: [String] = []
		if scenarioFailure != nil {
			failures.append("scenario reported failure")
		}
		if events.contains(where: { $0.name == "palette.results" && $0.attributes["top_result_matches_expected"] == "false" }) {
			failures.append("palette top result differed from expected")
		}
		if events.contains(where: { $0.name == "palette.results" && $0.attributes["result_count_matches_expected"] == "false" }) {
			failures.append("palette result count differed from expected")
		}
		if options.scenario == nil || options.scenario == "palette" {
			let samples = pairedDurations(events, start: "palette.query", end: "palette.results") { event in
				event.attributes["query_bytes"] != "0"
			}
			if samples.isEmpty {
				failures.append("no palette query/result pairs")
			} else {
				metrics["palette_query_to_results"] = traceMetric(samples)
			}
		}
		if options.scenario == nil || options.scenario == "scroll" {
			let samples = pairedDurations(events, start: "scroll.input", end: "scroll.render_commit")
			if samples.isEmpty {
				failures.append("no scroll input/render pairs")
			} else {
				metrics["scroll_input_to_render_commit"] = traceMetric(samples)
			}
		}
		let syntaxSamples = events.compactMap { event -> Double? in
			guard event.name == "syntax.refresh.end", let duration = event.attributes["duration_ns"], let value = Double(duration) else {
				return nil
			}
			return value / 1_000_000
		}
		if !syntaxSamples.isEmpty {
			metrics["syntax_refresh"] = traceMetric(syntaxSamples)
		}
		if events.isEmpty {
			failures.append("trace is empty or invalid")
		}
		return TraceReportResult(
			event_count: events.count,
			failure_reason: failures.isEmpty ? nil : failures.joined(separator: "; "),
			metadata: TraceReportMetadata(
				app_commit: options.appCommit,
				display: try display(DisplayOptions(displayID: options.displayID)),
				fixture_checksum: options.fixtureChecksum,
				hardware: sysctlString("hw.model"),
				macos: ProcessInfo.processInfo.operatingSystemVersionString,
				state: options.state
			),
			metrics: metrics,
			scenario: options.scenario,
			trace_path: options.tracePath
		)
	}

	private static func loadTraceEvents(path: String) -> [PerformanceTraceEvent] {
		guard let text = try? String(contentsOfFile: path, encoding: .utf8) else {
			return []
		}
		let decoder = JSONDecoder()
		return text.split(whereSeparator: \.isNewline).compactMap { line in
			try? decoder.decode(PerformanceTraceEvent.self, from: Data(line.utf8))
		}
	}

	private static func pairedDurations(
		_ events: [PerformanceTraceEvent],
		start: String,
		end: String,
		including includeStart: (PerformanceTraceEvent) -> Bool = { _ in true }
	) -> [Double] {
		let starts = Dictionary(uniqueKeysWithValues: events.filter { $0.name == start && includeStart($0) }.map { ($0.id, $0.timestampNS) })
		return events.compactMap { event in
			guard event.name == end, let startNS = starts[event.id], event.timestampNS >= startNS else {
				return nil
			}
			return Double(event.timestampNS - startNS) / 1_000_000
		}
	}

	private static func traceMetric(_ samples: [Double]) -> TraceMetric {
		let sorted = samples.sorted()
		let medianIndex = (sorted.count - 1) / 2
		let p95Index = min(sorted.count - 1, max(0, Int(ceil(Double(sorted.count) * 0.95)) - 1))
		return TraceMetric(
			max_ms: sorted.last ?? 0,
			median_ms: sorted[medianIndex],
			p95_ms: sorted[p95Index],
			raw_samples_ms: samples,
			sample_count: samples.count
		)
	}

	private static func sysctlString(_ name: String) -> String? {
		var size = 0
		guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else {
			return nil
		}
		var bytes = [CChar](repeating: 0, count: size)
		guard sysctlbyname(name, &bytes, &size, nil, 0) == 0 else {
			return nil
		}
		return String(cString: bytes)
	}

	private static func smoke(runs: Int) throws -> SmokeResult {
		var counts: [String: Int] = [:]
		for sample in highlightSmokeSamples {
			let url = URL(fileURLWithPath: sample.filename)
			guard let language = SyntaxPipeline.language(forFileURL: url) else {
				throw BenchError.smokeFailed("no language for \(sample.filename)")
			}
			var pipeline = SyntaxPipeline(language: language)
			let rope = Rope(sample.source)
			let tree = try pipeline.parse(rope)
			guard !tree.rootNode.hasError else {
				throw BenchError.smokeFailed("\(sample.filename) parsed with errors")
			}
			let spans = try pipeline.highlights(in: tree)
			guard !spans.isEmpty else {
				throw BenchError.smokeFailed("\(sample.filename) produced no highlight spans")
			}
			counts[sample.filename] = spans.count
		}
		return SmokeResult(highlight_span_counts: counts, mode: "smoke", runs: runs)
	}

	private static func workspaceFSEvents(_ options: WorkspaceFSEventsOptions) throws -> WorkspaceFSEventsResult {
		let root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-fsevents-smoke-\(UUID().uuidString)", isDirectory: true)
		let workspace = root.appendingPathComponent("workspace", isDirectory: true)
		let store = WorkspaceFSEventIDStore(directory: root.appendingPathComponent("store", isDirectory: true))
		try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
		defer {
			try? FileManager.default.removeItem(at: root)
		}
		let recorder = WorkspaceFSEventRecorder()
		let stream = WorkspaceFSEventStream(root: workspace, store: store, latency: 0.05, debounce: 0.05) { batch in
			recorder.record(batch)
		}
		guard stream.start() else {
			throw BenchError.smokeFailed("FSEvent stream did not start")
		}
		Thread.sleep(forTimeInterval: 0.2)
		let createBaseline = recorder.snapshot().batches
		let created = workspace.appendingPathComponent("created.swift")
		guard FileManager.default.createFile(atPath: created.path, contents: Data("struct Created {}\n".utf8)) else {
			stream.stop()
			throw BenchError.smokeFailed("fixture file create failed")
		}
		guard waitForFSEvent(options, recorder, minimumBatches: createBaseline + 1, condition: { $0.sawPath("created.swift", after: createBaseline) }) else {
			stream.stop()
			throw BenchError.smokeFailed("create event missing: \(recorder.snapshot().flagsByPath)")
		}
		let modifyBaseline = recorder.snapshot().batches
		let handle = try FileHandle(forWritingTo: created)
		try handle.seekToEnd()
		try handle.write(contentsOf: Data("func edited() {}\n".utf8))
		try handle.close()
		guard waitForFSEvent(options, recorder, minimumBatches: modifyBaseline + 1, condition: { $0.saw("created.swift", kFSEventStreamEventFlagItemModified, after: modifyBaseline) }) else {
			stream.stop()
			throw BenchError.smokeFailed("modify event missing")
		}
		let renameBaseline = recorder.snapshot().batches
		let renamed = workspace.appendingPathComponent("renamed.swift")
		try FileManager.default.moveItem(at: created, to: renamed)
		guard waitForFSEvent(options, recorder, minimumBatches: renameBaseline + 1, condition: { $0.saw("created.swift", kFSEventStreamEventFlagItemRenamed, after: renameBaseline) || $0.saw("renamed.swift", kFSEventStreamEventFlagItemRenamed, after: renameBaseline) }) else {
			stream.stop()
			throw BenchError.smokeFailed("rename event missing")
		}
		let removeBaseline = recorder.snapshot().batches
		try FileManager.default.removeItem(at: renamed)
		guard waitForFSEvent(options, recorder, minimumBatches: removeBaseline + 1, condition: { $0.saw("renamed.swift", kFSEventStreamEventFlagItemRemoved, after: removeBaseline) }) else {
			stream.stop()
			throw BenchError.smokeFailed("remove event missing")
		}
		guard let storedInitialID = store.eventID(for: workspace) else {
			stream.stop()
			throw BenchError.smokeFailed("stored event ID missing")
		}
		stream.stop()
		let offline = workspace.appendingPathComponent("offline.swift")
		guard FileManager.default.createFile(atPath: offline.path, contents: Data("struct Offline {}\n".utf8)) else {
			throw BenchError.smokeFailed("offline fixture file create failed")
		}
		Thread.sleep(forTimeInterval: 0.2)
		let resumeRecorder = WorkspaceFSEventRecorder()
		let resumeStream = WorkspaceFSEventStream(root: workspace, store: store, latency: 0.05, debounce: 0.05) { batch in
			resumeRecorder.record(batch)
		}
		guard resumeStream.start() else {
			throw BenchError.smokeFailed("resume FSEvent stream did not start")
		}
		defer {
			resumeStream.stop()
		}
		guard waitForFSEvent(options, resumeRecorder, minimumBatches: 1, condition: { $0.sawPath("offline.swift") }) else {
			throw BenchError.smokeFailed("resume event missing after stored ID \(UInt64(storedInitialID))")
		}
		let first = recorder.snapshot()
		let resumed = resumeRecorder.snapshot()
		return WorkspaceFSEventsResult(
			batches: first.batches + resumed.batches,
			create_seen: first.sawPath("created.swift"),
			modify_seen: first.saw("created.swift", kFSEventStreamEventFlagItemModified),
			remove_seen: first.saw("renamed.swift", kFSEventStreamEventFlagItemRemoved),
			rename_seen: first.saw("created.swift", kFSEventStreamEventFlagItemRenamed) || first.saw("renamed.swift", kFSEventStreamEventFlagItemRenamed),
			resume_seen: resumed.sawPath("offline.swift"),
			stored_event_id: store.eventID(for: workspace).map { UInt64($0) }
		)
	}

	private static func workspaceIndex(_ options: WorkspaceIndexOptions) throws -> WorkspaceIndexBenchResult {
		let root = FileManager.default.temporaryDirectory
			.appendingPathComponent("itsy-workspace-index-bench-\(UUID().uuidString)", isDirectory: true)
		let workspace = root.appendingPathComponent("workspace", isDirectory: true)
		let store = WorkspaceIndexStore(directory: root.appendingPathComponent("store", isDirectory: true))
		try FileManager.default.createDirectory(at: workspace, withIntermediateDirectories: true)
		defer {
			try? FileManager.default.removeItem(at: root)
		}
		try "ignored/\n".write(to: workspace.appendingPathComponent(".gitignore"), atomically: true, encoding: .utf8)
		for index in 0 ..< options.files {
			try writeWorkspaceIndexFixtureFile(root: workspace, path: "Sources/Group\(index / 100)/File\(index).swift", index: index)
		}
		for index in 0 ..< options.ignoredFiles {
			try writeWorkspaceIndexFixtureFile(root: workspace, path: "ignored/Group\(index / 100)/Ignored\(index).swift", index: index)
		}
		var built = WorkspaceIndex(root: workspace, files: [])
		let buildNS = measureNanoseconds {
			built = WorkspaceIndexer.build(root: workspace)
		}
		let saveNS = try measureNanoseconds {
			try store.save(built)
		}
		var loaded: WorkspaceIndex?
		let loadNS = try measureNanoseconds {
			loaded = try store.load(for: workspace)
		}
		guard let loaded else {
			throw BenchError.smokeFailed("persisted workspace index did not load")
		}
		let query = "IndexedType\(options.files - 1)"
		var results: [WorkspaceSymbol] = []
		let queryNS = measureNanoseconds {
			results = loaded.searchSymbols(query: query, limit: 10)
		}
		let queryMS = Double(queryNS) / 1_000_000
		return WorkspaceIndexBenchResult(
			build_ms: Double(buildNS) / 1_000_000,
			files_requested: options.files,
			first_symbol_query_ms: queryMS,
			first_symbol_query_under_100ms: queryMS < 100,
			ignored_files_requested: options.ignoredFiles,
			ignored_indexed_files: loaded.files.filter { $0.relativePath.hasPrefix("ignored/") }.count,
			indexed_files: loaded.files.count,
			load_ms: Double(loadNS) / 1_000_000,
			query_results: results.count,
			save_ms: Double(saveNS) / 1_000_000
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

	private static func workflow(_ options: WorkflowOptions) throws -> WorkflowBenchResult {
		let fileURL = URL(fileURLWithPath: options.file)
		let source = try Data(contentsOf: fileURL)
		guard !source.isEmpty else {
			throw BenchError.smokeFailed("workflow corpus is empty: \(fileURL.path)")
		}
		let queryLength = min(64, source.count)
		let queryStart = max(0, (source.count - queryLength) / 2)
		let query = Array(source[queryStart ..< queryStart + queryLength])
		let paneLayouts = ["L", "V[L,L]", "H[L,L]", "V[H[L,L],L]"]
		var samples: [WorkflowSample] = []
		for _ in 0 ..< options.repeats {
			let baselineRSS = try residentSizeKB(pid: getpid())
			var tree = PieceTree()
			let openNS = measureNanoseconds {
				tree = PieceTree(bytes: Array(source))
			}
			let editOffset = tree.length / 2
			let editNS = measureNanoseconds {
				tree.insert("itsy-workflow-edit", at: editOffset)
				tree.remove(editOffset ..< editOffset + "itsy-workflow-edit".utf8.count)
			}
			let searchNS = measureNanoseconds {
				_ = streamingSearch(query, in: tree)
			}
			guard streamingSearch(query, in: tree) != nil else {
				throw BenchError.smokeFailed("workflow search missed corpus content")
			}
			let saveURL = FileManager.default.temporaryDirectory
				.appendingPathComponent("itsy-workflow-\(UUID().uuidString)-\(fileURL.lastPathComponent)")
			let saveNS = try measureNanoseconds {
				try AtomicFileWriter.write(to: saveURL) { descriptor in
					try tree.write(to: descriptor, path: saveURL.path)
				}
			}
			defer { try? FileManager.default.removeItem(at: saveURL) }
			guard (try saveURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) == source.count else {
				throw BenchError.smokeFailed("workflow save changed corpus length")
			}
			let paneNS = try measureNanoseconds {
				var state = WorkspaceWindowState()
				for transition in 0 ..< options.paneTransitions {
					state.paneLayout = paneLayouts[transition % paneLayouts.count]
					state.paneStates = [
						WorkspacePaneState(openPaths: [fileURL.path], selectedPath: fileURL.path),
						WorkspacePaneState(openPaths: [fileURL.path], selectedPath: fileURL.path),
					]
					state.focusedPaneIndex = transition % 2
					state = try JSONDecoder().decode(WorkspaceWindowState.self, from: JSONEncoder().encode(state))
				}
			}
			let currentRSS = try residentSizeKB(pid: getpid())
			samples.append(WorkflowSample(
				edit_ms: Double(editNS) / 1_000_000,
				open_ms: Double(openNS) / 1_000_000,
				pane_transition_ms: Double(paneNS) / 1_000_000 / Double(options.paneTransitions),
				rss_delta_kb: rssDeltaKB(currentRSS, baselineRSS),
				save_ms: Double(saveNS) / 1_000_000,
				search_ms: Double(searchNS) / 1_000_000
			))
		}
		let metrics: [(String, [Double])] = [
			("open_ms", samples.map(\.open_ms)),
			("edit_ms", samples.map(\.edit_ms)),
			("search_ms", samples.map(\.search_ms)),
			("save_ms", samples.map(\.save_ms)),
			("pane_transition_ms", samples.map(\.pane_transition_ms)),
		]
		let means = Dictionary(uniqueKeysWithValues: metrics.map { name, values in
			(name, values.reduce(0, +) / Double(values.count))
		})
		let variance = Dictionary(uniqueKeysWithValues: metrics.map { name, values in
			let mean = means[name] ?? 0
			return (name, values.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(values.count))
		})
		return WorkflowBenchResult(
			corpus_bytes: source.count,
			corpus_path: fileURL.path,
			owner: "app",
			repeats: options.repeats,
			samples: samples,
			variance_ms2: variance,
			means_ms: means
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
		var mmapContractPassed: Bool?
		var mmapEditLength: Int?
		var mmapLineCount: Int?
		var mmapLoadMS: Double?
		var mmapPeakRSSDeltaKB: UInt64?
		var mmapSaveBytes: Int?
		var mmapSearchOffset: Int?
		if let file = options.mmapFile {
			let url = URL(fileURLWithPath: file)
			if FileManager.default.fileExists(atPath: url.path) {
				mmapPath = url.path
				let mmapBaselineRSS = options.mmapContract ? try residentSizeKB(pid: getpid()) : nil
				let loadNS = try measureNanoseconds {
					var mapped = try PieceTree(readingMappedFile: url)
					mmapBytes = mapped.length
					mmapLineCount = mapped.lineCount
					if options.mmapContract {
						let result = try mmapContract(
							tree: &mapped,
							sourceURL: url,
							baselineRSS: mmapBaselineRSS ?? 0,
							rssBudgetKB: options.mmapRSSBudgetKB
						)
						mmapContractPassed = result.passed
						mmapEditLength = result.editLength
						mmapPeakRSSDeltaKB = result.peakRSSDeltaKB
						mmapSaveBytes = result.saveBytes
						mmapSearchOffset = result.searchOffset
					}
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
			mmap_contract_passed: mmapContractPassed,
			mmap_edit_length: mmapEditLength,
			mmap_peak_rss_delta_kb: mmapPeakRSSDeltaKB,
			mmap_rss_budget_kb: options.mmapContract ? options.mmapRSSBudgetKB : nil,
			mmap_save_bytes: mmapSaveBytes,
			mmap_search_offset: mmapSearchOffset,
			operations: operations,
			random_insert_ns_per_op: Double(randomNS) / Double(operations),
			random_remove_ns_per_op: Double(removeNS) / Double(operations),
			sequential_insert_ns_per_op: Double(sequentialNS) / Double(operations),
			slice_length: sliceLength,
			slice_ns_per_op: Double(sliceNS) / Double(operations),
			slice_checksum: checksum
		)
	}

	private static func mmapContract(
		tree: inout PieceTree,
		sourceURL: URL,
		baselineRSS: UInt64,
		rssBudgetKB: UInt64
	) throws -> MMapContractResult {
		var peakRSS = baselineRSS
		let expectedLength = tree.length
		let queryLength = min(64, expectedLength)
		guard queryLength > 0 else {
			return MMapContractResult(editLength: 0, passed: false, peakRSSDeltaKB: 0, saveBytes: 0, searchOffset: nil)
		}
		let queryOffset = min(max(0, expectedLength / 2), expectedLength - queryLength)
		var query = [UInt8](repeating: 0, count: queryLength)
		let copied = query.withUnsafeMutableBufferPointer { tree.copyUTF8(at: queryOffset, into: $0) }
		query.removeSubrange(copied ..< query.count)
		_ = try samplePeakRSS(pid: getpid(), peakRSS: &peakRSS)
		let searchOffset = streamingSearch(query, in: tree)
		_ = try samplePeakRSS(pid: getpid(), peakRSS: &peakRSS)

		let insertion = Array("itsy-large-text-contract".utf8)
		tree.insert(insertion, at: queryOffset)
		tree.remove(queryOffset ..< queryOffset + insertion.count)
		let editLength = tree.length
		_ = try samplePeakRSS(pid: getpid(), peakRSS: &peakRSS)

		let saveURL = sourceURL.deletingLastPathComponent()
			.appendingPathComponent(".\(sourceURL.lastPathComponent).itsy-contract-\(UUID().uuidString)")
		defer { try? FileManager.default.removeItem(at: saveURL) }
		try AtomicFileWriter.write(to: saveURL) { descriptor in
			try tree.write(to: descriptor, path: saveURL.path)
		}
		let saveBytes = try saveURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
		_ = try samplePeakRSS(pid: getpid(), peakRSS: &peakRSS)
		let peakRSSDeltaKB = rssDeltaKB(peakRSS, baselineRSS)
		return MMapContractResult(
			editLength: editLength,
			passed: searchOffset == queryOffset && editLength == expectedLength && saveBytes == expectedLength && peakRSSDeltaKB <= rssBudgetKB,
			peakRSSDeltaKB: peakRSSDeltaKB,
			saveBytes: saveBytes,
			searchOffset: searchOffset
		)
	}

	private static func streamingSearch(_ query: [UInt8], in tree: PieceTree) -> Int? {
		guard !query.isEmpty else {
			return 0
		}
		var failures = [Int](repeating: 0, count: query.count)
		for index in 1 ..< query.count {
			var candidate = failures[index - 1]
			while candidate > 0, query[index] != query[candidate] {
				candidate = failures[candidate - 1]
			}
			if query[index] == query[candidate] {
				candidate += 1
			}
			failures[index] = candidate
		}
		var matched = 0
		var offset = 0
		var result: Int?
		tree.iterateBytes(from: 0) { bytes in
			for byte in bytes {
				while matched > 0, byte != query[matched] {
					matched = failures[matched - 1]
				}
				if byte == query[matched] {
					matched += 1
				}
				if matched == query.count {
					result = offset - query.count + 1
					return false
				}
				offset += 1
			}
			return true
		}
		return result
	}

	private static func undo(_ options: UndoOptions) throws -> UndoBenchResult {
		let replacements = ["b", "c", "d", "e"]
		let pid = getpid()
		var editor = Editor(pieceTree: PieceTree(bytes: [UInt8](repeating: 97, count: options.bufferBytes)))
		editor.history = UndoStack(maxEditCount: options.operations, maxTotalRemovedBytes: Int.max)
		var peakRSS = try residentSizeKB(pid: pid)
		let baselineRSS = peakRSS
		let sampleInterval = 1_024
		let recordNS = try measureNanoseconds {
			for operation in 0 ..< options.operations {
				editor.setSelection(SelectionSet(primary: Selection(anchor: 0, head: 1)))
				editor.insert(replacements[operation % replacements.count])
				if operation % sampleInterval == 0 {
					_ = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
				}
			}
		}
		let afterRecordRSS = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
		let retainedUndoEntries = editor.history.edits.count
		let undoNS = try measureNanoseconds {
			for operation in 0 ..< options.operations {
				editor.undo()
				if operation % sampleInterval == 0 {
					_ = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
				}
			}
		}
		let afterUndoRSS = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
		let redoNS = try measureNanoseconds {
			for operation in 0 ..< options.operations {
				editor.redo()
				if operation % sampleInterval == 0 {
					_ = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
				}
			}
		}
		let afterRedoRSS = try samplePeakRSS(pid: pid, peakRSS: &peakRSS)
		let maxDelta = rssDeltaKB(peakRSS, baselineRSS)
		return UndoBenchResult(
			after_record_rss_delta_kb: rssDeltaKB(afterRecordRSS, baselineRSS),
			after_record_rss_kb: afterRecordRSS,
			after_redo_rss_delta_kb: rssDeltaKB(afterRedoRSS, baselineRSS),
			after_redo_rss_kb: afterRedoRSS,
			after_undo_rss_delta_kb: rssDeltaKB(afterUndoRSS, baselineRSS),
			after_undo_rss_kb: afterUndoRSS,
			baseline_rss_kb: baselineRSS,
			buffer_bytes: options.bufferBytes,
			final_checksum: editorChecksum(editor),
			final_length: editor.textStorage.length,
			final_undo_entries: editor.history.edits.count,
			max_rss_delta_kb: maxDelta,
			operations: options.operations,
			record_ns_per_op: Double(recordNS) / Double(options.operations),
			retained_undo_entries: retainedUndoEntries,
			rss_budget_kb: options.rssBudgetKB,
			rss_budget_passed: maxDelta <= options.rssBudgetKB,
			sampled_peak_rss_kb: peakRSS,
			redo_ns_per_op: Double(redoNS) / Double(options.operations),
			undo_ns_per_op: Double(undoNS) / Double(options.operations)
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
		var samples: [MeasureSampleResult] = []
		for _ in 0 ..< options.runs {
			samples.append(try measureOnce(options))
		}
		guard let first = samples.first else {
			throw BenchError.usage("invalid --runs")
		}
		let firstWindowValues = samples.map(\.first_window_visible_ms)
		let externalValues = samples.map(\.external_first_window_visible_ms)
		let appDidValues = samples.compactMap(\.app_did_finish_launching_to_first_window_visible_ms)
		let processValues = samples.compactMap(\.process_start_to_first_window_visible_ms)
		return MeasureResult(
			app: first.app,
			app_did_finish_launching_to_first_window_visible_ms: mean(appDidValues),
			external_first_window_visible_ms: mean(externalValues) ?? first.external_first_window_visible_ms,
			external_first_window_visible_max_ms: externalValues.max(),
			external_first_window_visible_min_ms: externalValues.min(),
			first_window_visible_max_ms: firstWindowValues.max(),
			first_window_visible_min_ms: firstWindowValues.min(),
			first_window_visible_ms: mean(firstWindowValues) ?? first.first_window_visible_ms,
			process_start_to_first_window_visible_ms: mean(processValues),
			rss_kb: UInt64(samples.map(\.rss_kb).reduce(0, +) / UInt64(samples.count)),
			runs: samples.count,
			samples: options.runs > 1 ? samples : nil,
			stage_ms: options.runs == 1 ? first.stage_ms : nil,
			startup_ms: mean(externalValues) ?? first.startup_ms
		)
	}

	private static func measureOnce(_ options: MeasureOptions) throws -> MeasureSampleResult {
		try requireAccessibility()
		let url = URL(fileURLWithPath: options.app)
		let start = DispatchTime.now().uptimeNanoseconds
		let deadline = Date(timeIntervalSinceNow: Double(options.timeoutMS) / 1000)
		let stageURL = options.staged ? temporaryStageURL() : nil
		if let stageURL {
			FileManager.default.createFile(atPath: stageURL.path, contents: nil)
		}
		let environment = stageURL.map { ["ITSY_BENCH_STAGES_PATH": $0.path] } ?? [:]
		let app = try launch(
			url: url,
			args: options.args,
			newInstance: options.newInstance,
			environment: environment,
			deadline: deadline
		)
		defer { terminate(app) }
		let firstWindow = try waitForFirstWindow(pid: app.processIdentifier, start: start, deadline: deadline)
		if let stageURL {
			_ = waitForStage("first_draw", at: stageURL, deadline: deadline)
		}
		let rawStages = stageURL.map { loadRawStages(from: $0) }
		let startup = milliseconds(from: start, to: firstWindow)
		let processStartToVisible = rawStages?["process_start"].map { milliseconds(from: $0, to: firstWindow) }
		let appDidFinishToVisible = rawStages?["app_did_finish_launching"].map { milliseconds(from: $0, to: firstWindow) }
		let firstWindowVisible = options.staged ? appDidFinishToVisible ?? processStartToVisible ?? startup : startup
		return MeasureSampleResult(
			app: url.lastPathComponent,
			app_did_finish_launching_to_first_window_visible_ms: appDidFinishToVisible,
			external_first_window_visible_ms: startup,
			first_window_visible_ms: firstWindowVisible,
			process_start_to_first_window_visible_ms: processStartToVisible,
			rss_kb: try residentSizeKB(pid: app.processIdentifier),
			stage_ms: stageURL.map { loadStages(from: $0, since: start) },
			startup_ms: startup
		)
	}

	private static func mean(_ values: [Double]) -> Double? {
		guard !values.isEmpty else {
			return nil
		}
		return values.reduce(0, +) / Double(values.count)
	}

	private static func milliseconds(from start: UInt64, to end: UInt64) -> Double {
		Double(end - min(start, end)) / 1_000_000
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
		let resultBox = OpenApplicationResultBox()
		NSWorkspace.shared.openApplication(at: url, configuration: config) { app, error in
			resultBox.store(app: app, error: error)
			semaphore.signal()
		}
		guard semaphore.wait(timeout: dispatchDeadline(deadline)) == .success else {
			throw BenchError.launchTimeout
		}
		let (runningApp, launchError) = resultBox.snapshot()
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
		let opts = ["AXTrustedCheckOptionPrompt": false] as CFDictionary
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

	fileprivate static func residentSizeKB(pid: Int32) throws -> UInt64 {
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
	  itsybench measure --app <path> [--args <arg>] [--new-instance] [--runs <count>] [--staged] [--timeout-ms <ms>] [--warmup-purge]
	  itsybench open --file <path> [--app <path>] [--timeout-ms <ms>] [--warmup-purge]
	  itsybench piecetree [--ops <count>] [--slice-length <bytes>] [--file <path>]
	  itsybench render-highlight-cache [--lines <count>] [--frames <count>]
	  itsybench rope [--ops <count>] [--slice-length <bytes>]
	  itsybench rss --pid <pid>
	  itsybench undo [--ops <count>] [--buffer-bytes <bytes>] [--rss-budget-kb <kb>]
	  itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
	  itsybench workspace-fsevents [--timeout-ms <ms>]
	  itsybench workspace-index [--files <count>] [--ignored-files <count>]
	  itsybench workflow --file <path> [--repeats <count>] [--pane-transitions <count>]
	"""
}

private let highlightSmokeSamples = [
	HighlightSmokeSample(filename: "sample.swift", source: """
	import Foundation
	let answer = 42
	func smoke() -> Int { answer }
	"""),
	HighlightSmokeSample(filename: "sample.zig", source: """
	const std = @import("std");
	pub fn main() void {
	    const answer: i32 = 42;
	    _ = answer;
	}
	"""),
	HighlightSmokeSample(filename: "sample.bash", source: """
	#!/usr/bin/env bash
	set -euo pipefail
	echo "$HOME"
	"""),
	HighlightSmokeSample(filename: "sample.sql", source: """
	select id, name from users where active = true order by id;
	"""),
]

@MainActor private func samplePeakRSS(pid: Int32, peakRSS: inout UInt64) throws -> UInt64 {
	let value = try ItsyBenchMain.residentSizeKB(pid: pid)
	peakRSS = max(peakRSS, value)
	return value
}

private func rssDeltaKB(_ value: UInt64, _ baseline: UInt64) -> UInt64 {
	value > baseline ? value - baseline : 0
}

private func editorChecksum(_ editor: Editor) -> Int {
	switch editor.textStorage {
	case let .pieceTree(pieceTree):
		guard pieceTree.length > 0 else {
			return 0
		}
		let step = max(1, pieceTree.length / 64)
		return Swift.stride(from: 0, to: pieceTree.length, by: step).reduce(pieceTree.length) {
			$0 &+ Int(pieceTree.utf8Byte(at: $1))
		}
	case let .rope(rope):
		return rope.length
	}
}

private func writeWorkspaceIndexFixtureFile(root: URL, path: String, index: Int) throws {
	let url = root.appendingPathComponent(path)
	try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
	try """
	struct IndexedType\(index) {
		func indexedFunction\(index)() {}
	}
	""".write(to: url, atomically: true, encoding: .utf8)
}

private func waitForFSEvent(
	_ options: WorkspaceFSEventsOptions,
	_ recorder: WorkspaceFSEventRecorder,
	minimumBatches: Int = 0,
	condition: (WorkspaceFSEventSnapshot) -> Bool
) -> Bool {
	let deadline = Date(timeIntervalSinceNow: Double(options.timeoutMS) / 1000)
	while Date() < deadline {
		let snapshot = recorder.snapshot()
		if snapshot.batches >= minimumBatches, condition(snapshot) {
			return true
		}
		Thread.sleep(forTimeInterval: 0.025)
	}
	let snapshot = recorder.snapshot()
	return snapshot.batches >= minimumBatches && condition(snapshot)
}

private struct WorkspaceFSEventSnapshot {
	var batches: Int
	var events: [WorkspaceFSEventSeen]
	var flagsByPath: [String: UInt32]

	func saw(_ filename: String, _ flag: Int, after batch: Int = 0) -> Bool {
		events.contains { event in
			event.batch > batch && event.filename == filename && event.flags & UInt32(flag) != 0
		}
	}

	func sawPath(_ filename: String, after batch: Int = 0) -> Bool {
		events.contains { event in
			event.batch > batch && event.filename == filename
		}
	}
}

private struct WorkspaceFSEventSeen {
	var batch: Int
	var filename: String
	var flags: UInt32
}

private final class WorkspaceFSEventRecorder: @unchecked Sendable {
	private let lock = NSLock()
	private var batches = 0
	private var events: [WorkspaceFSEventSeen] = []
	private var flagsByPath: [String: UInt32] = [:]

	func record(_ batch: WorkspaceFileEventBatch) {
		lock.lock()
		defer {
			lock.unlock()
		}
		batches += 1
		for event in batch.events {
			let filename = event.url.lastPathComponent
			let flags = UInt32(event.flags)
			events.append(WorkspaceFSEventSeen(batch: batches, filename: filename, flags: flags))
			flagsByPath[filename, default: 0] |= flags
		}
	}

	func snapshot() -> WorkspaceFSEventSnapshot {
		lock.lock()
		defer {
			lock.unlock()
		}
		return WorkspaceFSEventSnapshot(batches: batches, events: events, flagsByPath: flagsByPath)
	}
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
