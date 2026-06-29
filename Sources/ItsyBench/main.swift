import AppKit
import ApplicationServices
import CoreVideo
import Darwin
import Dispatch
import Foundation
import ItsyEditor

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
	var warmupPurge: Bool
}

private struct RopeOptions {
	var operations: Int
	var sliceLength: Int
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
	var startup_ms: Double
	var rss_kb: UInt64
	var app: String
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
		case "latency":
			try printJSON(latency(parseLatency(Array(args.dropFirst()))))
		case "measure":
			try printJSON(measure(parseMeasure(Array(args.dropFirst()))))
		case "rope":
			try printJSON(rope(parseRope(Array(args.dropFirst()))))
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
			case "--warmup-purge":
				warmupPurge = true
				index = args.index(after: index)
			default:
				throw BenchError.usage("unknown measure option: \(arg)")
			}
		}
		guard let app else {
			throw BenchError.usage("usage: itsybench measure --app <path> [--args <arg>] [--new-instance] [--warmup-purge]")
		}
		return MeasureOptions(app: app, args: appArgs, newInstance: newInstance, warmupPurge: warmupPurge)
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
		let deadline = Date(timeIntervalSinceNow: 5)
		let app = try launch(url: url, args: options.args, newInstance: options.newInstance, deadline: deadline)
		defer { terminate(app) }
		let firstWindow = try waitForFirstWindow(pid: app.processIdentifier, start: start, deadline: deadline)
		let startup = Double(firstWindow - start) / 1_000_000
		return MeasureResult(startup_ms: startup, rss_kb: try residentSizeKB(pid: app.processIdentifier), app: url.lastPathComponent)
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

	private static func launch(url: URL, args: [String], newInstance: Bool, deadline: Date) throws -> NSRunningApplication {
		let config = NSWorkspace.OpenConfiguration()
		config.arguments = args
		config.activates = false
		config.addsToRecentItems = false
		config.createsNewApplicationInstance = newInstance
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

	private static func waitForFirstWindow(pid: pid_t, start: UInt64, deadline: Date) throws -> UInt64 {
		let element = AXUIElementCreateApplication(pid)
		let waiter = WindowWaiter()
		var observer: AXObserver?
		var observerError: AXError = .failure
		while Date() < deadline {
			observerError = AXObserverCreate(pid, axCallback, &observer)
			if observerError == .success, observer != nil {
				break
			}
			Thread.sleep(forTimeInterval: 0.02)
		}
		guard observerError == .success, let observer else {
			throw BenchError.axObserverFailed(observerError)
		}
		let source = AXObserverGetRunLoopSource(observer)
		CFRunLoopAddSource(CFRunLoopGetCurrent(), source, .defaultMode)
		let refcon = UnsafeMutableRawPointer(Unmanaged.passUnretained(waiter).toOpaque())
		var notificationAdded = false
		var lastNotifyError: AXError = .success
		while !notificationAdded, Date() < deadline {
			if hasWindow(element) {
				waiter.mark(DispatchTime.now().uptimeNanoseconds)
				break
			}
			lastNotifyError = AXObserverAddNotification(observer, element, kAXWindowCreatedNotification as CFString, refcon)
			if lastNotifyError == .success {
				notificationAdded = true
			} else {
				Thread.sleep(forTimeInterval: 0.02)
			}
		}
		if !notificationAdded, !waiter.hasValue {
			CFRunLoopRemoveSource(CFRunLoopGetCurrent(), source, .defaultMode)
			throw BenchError.axNotificationFailed(lastNotifyError)
		}
		defer {
			if notificationAdded {
				AXObserverRemoveNotification(observer, element, kAXWindowCreatedNotification as CFString)
			}
			CFRunLoopRemoveSource(CFRunLoopGetCurrent(), source, .defaultMode)
		}
		if hasWindow(element) {
			waiter.mark(DispatchTime.now().uptimeNanoseconds)
		}
		while !waiter.hasValue, Date() < deadline {
			RunLoop.current.run(mode: .default, before: Date(timeIntervalSinceNow: 0.01))
		}
		guard let timestamp = waiter.timestamp else {
			throw BenchError.windowTimeout
		}
		return max(timestamp, start)
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
	  itsybench measure --app <path> [--args <arg>] [--new-instance] [--warmup-purge]
	  itsybench rope [--ops <count>] [--slice-length <bytes>]
	  itsybench rss --pid <pid>
	  itsybench latency --pid <pid> [--key-code <code>] [--display <id>] [--timeout-ms <ms>] [--dirty-rects <n>]
	"""
}

private func measureNanoseconds(_ body: () -> Void) -> UInt64 {
	let start = DispatchTime.now().uptimeNanoseconds
	body()
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
