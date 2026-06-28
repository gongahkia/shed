import AppKit
import ApplicationServices
import Darwin
import Dispatch
import Foundation

private struct MeasureOptions {
	var app: String
	var args: [String]
	var warmupPurge: Bool
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
enum PicoBenchMain {
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
		guard let command = args.first else {
			throw BenchError.usage("usage: picobench measure --app <path> [--args <arg>] [--warmup-purge] | picobench rss --pid <pid>")
		}
		switch command {
		case "measure":
			try printJSON(measure(parseMeasure(Array(args.dropFirst()))))
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
			throw BenchError.usage("usage: picobench rss --pid <pid>")
		}
		let valueIndex = args.index(after: index)
		guard valueIndex < args.endIndex, let pid = Int32(args[valueIndex]) else {
			throw BenchError.badPID(valueIndex < args.endIndex ? args[valueIndex] : "")
		}
		return pid
	}

	private static func parseMeasure(_ args: [String]) throws -> MeasureOptions {
		var app: String?
		var appArgs: [String] = []
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
			case "--warmup-purge":
				warmupPurge = true
				index = args.index(after: index)
			default:
				throw BenchError.usage("unknown measure option: \(arg)")
			}
		}
		guard let app else {
			throw BenchError.usage("usage: picobench measure --app <path> [--args <arg>] [--warmup-purge]")
		}
		return MeasureOptions(app: app, args: appArgs, warmupPurge: warmupPurge)
	}

	private static func measure(_ options: MeasureOptions) throws -> MeasureResult {
		if options.warmupPurge {
			try purgeMemory()
		}
		try requireAccessibility()
		let url = URL(fileURLWithPath: options.app)
		let start = DispatchTime.now().uptimeNanoseconds
		let deadline = Date(timeIntervalSinceNow: 5)
		let app = try launch(url: url, args: options.args, deadline: deadline)
		defer { app.terminate() }
		let firstWindow = try waitForFirstWindow(pid: app.processIdentifier, start: start, deadline: deadline)
		let startup = Double(firstWindow - start) / 1_000_000
		return MeasureResult(startup_ms: startup, rss_kb: try residentSizeKB(pid: app.processIdentifier), app: url.lastPathComponent)
	}

	private static func launch(url: URL, args: [String], deadline: Date) throws -> NSRunningApplication {
		let config = NSWorkspace.OpenConfiguration()
		config.arguments = args
		config.activates = false
		config.addsToRecentItems = false
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
		let observerError = AXObserverCreate(pid, axCallback, &observer)
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

private let axCallback: AXObserverCallback = { _, _, notification, refcon in
	guard notification as String == kAXWindowCreatedNotification, let refcon else {
		return
	}
	let waiter = Unmanaged<WindowWaiter>.fromOpaque(refcon).takeUnretainedValue()
	waiter.mark(DispatchTime.now().uptimeNanoseconds)
}
