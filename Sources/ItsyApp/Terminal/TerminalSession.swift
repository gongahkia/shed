// @file PTY-backed terminal process session.
import Darwin
import Dispatch
import Foundation
import ItsyEditor

@_silgen_name("proc_listchildpids")
private func proc_listchildpids(_ parentPID: pid_t, _ buffer: UnsafeMutableRawPointer?, _ bufferSize: Int32) -> Int32

@_silgen_name("proc_listpids")
private func proc_listpids(_ type: UInt32, _ typeInfo: UInt32, _ buffer: UnsafeMutableRawPointer?, _ bufferSize: Int32) -> Int32

final class ItsyTerminalSession {
	let currentDirectoryURL: URL
	private let shellURL: URL
	private let baseEnvironment: [String: String]
	private let queue = DispatchQueue(label: "dev.itsy.terminal.session", qos: .userInitiated)
	private let stateLock = NSLock()
	private var masterFD: Int32 = -1
	private var childPID: pid_t = -1
	private var readSource: DispatchSourceRead?
	private var writeSource: DispatchSourceWrite?
	private var waitSource: DispatchSourceProcess?
	private var pendingInput = Data()
	private var isTerminating = false
	private var lifetimeRetainer: ItsyTerminalSession?
	var onOutput: ((Data) -> Void)?
	var onExit: ((Int32) -> Void)?

	init(currentDirectoryURL: URL, environment: [String: String] = ProcessInfo.processInfo.environment) {
		self.currentDirectoryURL = currentDirectoryURL
		baseEnvironment = environment
		let shellPath = environment["SHELL"].flatMap { $0.isEmpty ? nil : $0 } ?? "/bin/zsh"
		shellURL = URL(fileURLWithPath: shellPath)
	}

	var isRunning: Bool {
		stateLock.lock()
		defer { stateLock.unlock() }
		return childPID > 0
	}

	var processIdentifier: pid_t? {
		stateLock.lock()
		defer { stateLock.unlock() }
		return childPID > 0 ? childPID : nil
	}

	func start(columns: Int, rows: Int) throws {
		guard !isRunning else {
			resize(columns: columns, rows: rows)
			return
		}
		let shellCString = strdup(shellURL.path)
		let cwdCString = strdup(currentDirectoryURL.path)
		let loginCString = strdup("-il")
		let environment = ItsyTerminalEnvironment.build(from: baseEnvironment, shellPath: shellURL.path)
		let envStorage = environment.keys.sorted().map { key in
			strdup("\(key)=\(environment[key] ?? "")")
		}
		defer {
			free(shellCString)
			free(cwdCString)
			free(loginCString)
			for value in envStorage {
				free(value)
			}
		}
		guard let shellCString, let cwdCString, let loginCString, !envStorage.contains(where: { $0 == nil }) else {
			reportHealth(lifecycle: .stopped, state: .unavailable, lastError: "Unable to allocate terminal startup resources.", remediation: "Close unused applications and retry.")
			throw POSIXError(.ENOMEM)
		}
		var argv: [UnsafeMutablePointer<CChar>?] = [shellCString, loginCString, nil]
		var envp: [UnsafeMutablePointer<CChar>?] = envStorage + [nil]
		var master: Int32 = -1
		var size = winsize(
			ws_row: UInt16(max(1, rows)),
			ws_col: UInt16(max(1, columns)),
			ws_xpixel: 0,
			ws_ypixel: 0
		)
		let pid = argv.withUnsafeMutableBufferPointer { argvBuffer in
			envp.withUnsafeMutableBufferPointer { envBuffer in
				let pid = forkpty(&master, nil, nil, &size)
				if pid == 0 {
					_ = chdir(cwdCString)
					execve(shellCString, argvBuffer.baseAddress, envBuffer.baseAddress)
					_exit(127)
				}
				return pid
			}
		}
		guard pid >= 0 else {
			reportHealth(lifecycle: .stopped, state: .unavailable, lastError: "Terminal process could not start.", remediation: "Verify the configured shell and retry.")
			throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
		}
		let flags = fcntl(master, F_GETFL)
		guard flags >= 0, fcntl(master, F_SETFL, flags | O_NONBLOCK) == 0 else {
			let failure = POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
			Self.signalProcessGroup(pid, signal: SIGHUP)
			Darwin.close(master)
			var status: Int32 = 0
			_ = waitpid(pid, &status, 0)
			reportHealth(lifecycle: .stopped, state: .degraded, lastError: "Terminal process setup failed.", remediation: "Restart the terminal.")
			throw failure
		}
		stateLock.lock()
		masterFD = master
		childPID = pid
		isTerminating = false
		lifetimeRetainer = self
		stateLock.unlock()
		startReadSource(fileDescriptor: master)
		startWaitSource(pid: pid, fileDescriptor: master)
		reportHealth(lifecycle: .running, state: .healthy)
	}

	func send(_ data: Data) {
		guard !data.isEmpty else {
			return
		}
		queue.async { [weak self] in
			self?.enqueueInput(data)
		}
	}

	func resize(columns: Int, rows: Int) {
		queue.async { [weak self] in
			guard let self, let fileDescriptor = self.currentMasterFD(), let pid = self.currentProcessID() else {
				return
			}
			var size = winsize(
				ws_row: UInt16(max(1, rows)),
				ws_col: UInt16(max(1, columns)),
				ws_xpixel: 0,
				ws_ypixel: 0
			)
			guard self.isCurrentMaster(fileDescriptor) else {
				return
			}
			_ = ioctl(fileDescriptor, TIOCSWINSZ, &size)
			Self.signalProcessGroup(pid, signal: SIGWINCH)
		}
	}

	func terminate() {
		guard let pid = currentProcessID() else {
			return
		}
		stateLock.lock()
		isTerminating = true
		stateLock.unlock()
		reportHealth(lifecycle: .stopping, state: .retrying)
		Self.signalProcessTree(pid, signal: SIGHUP)
		Self.signalProcessTree(pid, signal: SIGTERM)
		queue.async { [weak self] in
			self?.stopIO()
		}
		queue.asyncAfter(deadline: .now() + .milliseconds(750)) { [weak self] in
			guard let self, self.isTerminatingProcess(pid) else {
				return
			}
			Self.signalProcessTree(pid, signal: SIGKILL)
		}
	}

	deinit {
		terminate()
	}

	private func startReadSource(fileDescriptor: Int32) {
		let source = DispatchSource.makeReadSource(fileDescriptor: fileDescriptor, queue: queue)
		source.setEventHandler { [weak self] in
			self?.readAvailableBytes(fileDescriptor: fileDescriptor)
		}
		source.setCancelHandler { [weak self] in
			self?.closeMaster(fileDescriptor)
		}
		readSource = source
		source.resume()
	}

	private func startWaitSource(pid: pid_t, fileDescriptor: Int32) {
		let source = DispatchSource.makeProcessSource(identifier: pid, eventMask: .exit, queue: queue)
		source.setEventHandler { [weak self] in
			self?.reapProcess(pid: pid, fileDescriptor: fileDescriptor)
		}
		waitSource = source
		source.resume()
	}

	private func enqueueInput(_ data: Data) {
		guard let fileDescriptor = currentMasterFD() else {
			return
		}
		pendingInput.append(data)
		drainPendingInput(fileDescriptor: fileDescriptor)
		guard !pendingInput.isEmpty, writeSource == nil else {
			return
		}
		let source = DispatchSource.makeWriteSource(fileDescriptor: fileDescriptor, queue: queue)
		source.setEventHandler { [weak self] in
			self?.drainPendingInput(fileDescriptor: fileDescriptor)
		}
		writeSource = source
		source.resume()
	}

	private func drainPendingInput(fileDescriptor: Int32) {
		while !pendingInput.isEmpty, isCurrentMaster(fileDescriptor) {
			let count = pendingInput.withUnsafeBytes { bytes -> Int in
				guard let base = bytes.baseAddress else {
					return 0
				}
				return Darwin.write(fileDescriptor, base, pendingInput.count)
			}
			if count > 0 {
				pendingInput.removeFirst(count)
				continue
			}
			if count < 0, errno == EINTR {
				continue
			}
			if count < 0, errno == EAGAIN || errno == EWOULDBLOCK {
				return
			}
			pendingInput.removeAll(keepingCapacity: false)
			writeSource?.cancel()
			writeSource = nil
			return
		}
		if pendingInput.isEmpty {
			writeSource?.cancel()
			writeSource = nil
		}
	}

	private func readAvailableBytes(fileDescriptor: Int32) {
		var buffer = [UInt8](repeating: 0, count: 16384)
		while isCurrentMaster(fileDescriptor) {
			let count = Darwin.read(fileDescriptor, &buffer, buffer.count)
			if count > 0 {
				onOutput?(Data(buffer.prefix(count)))
				continue
			}
			if count < 0, errno == EINTR {
				continue
			}
			if count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
				closeMaster(fileDescriptor)
				readSource?.cancel()
				readSource = nil
			}
			break
		}
	}

	private func stopIO() {
		pendingInput.removeAll(keepingCapacity: false)
		writeSource?.cancel()
		writeSource = nil
		readSource?.cancel()
		readSource = nil
		if let fileDescriptor = currentMasterFD() {
			closeMaster(fileDescriptor)
		}
	}

	private func reapProcess(pid: pid_t, fileDescriptor: Int32) {
		var status: Int32 = 0
		var result: pid_t = -1
		repeat {
			result = waitpid(pid, &status, 0)
		} while result < 0 && errno == EINTR
		guard result == pid else {
			return
		}
		stateLock.lock()
		guard childPID == pid else {
			stateLock.unlock()
			return
		}
		childPID = -1
		let terminatedByUser = isTerminating
		isTerminating = false
		let exitHandler = onExit
		lifetimeRetainer = nil
		stateLock.unlock()
		stopIO()
		waitSource?.cancel()
		waitSource = nil
		let exitCode = Self.exitCode(fromWaitStatus: status)
		exitHandler?(exitCode)
		reportHealth(
			lifecycle: .stopped,
			state: terminatedByUser || exitCode == 0 ? .healthy : .degraded,
			lastError: terminatedByUser || exitCode == 0 ? nil : "Terminal exited with status \(exitCode).",
			remediation: terminatedByUser || exitCode == 0 ? nil : "Restart the terminal."
		)
	}

	private func reportHealth(lifecycle: IntegrationLifecycle, state: IntegrationHealthState, lastError: String? = nil, remediation: String? = nil) {
		let directory = currentDirectoryURL.path
		Task {
			await IntegrationHealthStore.shared.report(
				service: .terminal,
				identifier: directory,
				lifecycle: lifecycle,
				state: state,
				lastError: lastError,
				remediation: remediation,
				detailLogReference: "terminal://\(directory)"
			)
		}
	}

	private func currentMasterFD() -> Int32? {
		stateLock.lock()
		defer { stateLock.unlock() }
		return masterFD >= 0 ? masterFD : nil
	}

	private func currentProcessID() -> pid_t? {
		stateLock.lock()
		defer { stateLock.unlock() }
		return childPID > 0 ? childPID : nil
	}

	private func isCurrentMaster(_ fileDescriptor: Int32) -> Bool {
		stateLock.lock()
		defer { stateLock.unlock() }
		return masterFD == fileDescriptor
	}

	private func isTerminatingProcess(_ pid: pid_t) -> Bool {
		stateLock.lock()
		defer { stateLock.unlock() }
		return childPID == pid && isTerminating
	}

	private func closeMaster(_ fileDescriptor: Int32) {
		stateLock.lock()
		guard masterFD == fileDescriptor else {
			stateLock.unlock()
			return
		}
		masterFD = -1
		stateLock.unlock()
		Darwin.close(fileDescriptor)
	}

	private static func signalProcessGroup(_ pid: pid_t, signal: Int32) {
		guard pid > 0 else {
			return
		}
		let processGroup = getpgid(pid)
		if processGroup > 0, Darwin.kill(-processGroup, signal) == 0 {
			return
		}
		if Darwin.kill(-pid, signal) != 0 {
			_ = Darwin.kill(pid, signal)
		}
	}

	private static func signalProcessTree(_ pid: pid_t, signal: Int32) {
		var processIDs = Set(descendantProcessIDs(of: pid))
		if getsid(pid) == pid {
			processIDs.formUnion(sessionProcessIDs(sessionID: pid))
		}
		processIDs.insert(pid)
		for childPID in processIDs {
			signalProcessGroup(childPID, signal: signal)
		}
	}

	private static func descendantProcessIDs(of rootPID: pid_t) -> [pid_t] {
		var descendants: [pid_t] = []
		var pending: [pid_t] = [rootPID]
		while let parentPID = pending.popLast() {
			let children = childProcessIDs(of: parentPID)
			descendants.append(contentsOf: children)
			pending.append(contentsOf: children)
		}
		return descendants
	}

	private static func childProcessIDs(of parentPID: pid_t) -> [pid_t] {
		var capacity = 16
		while capacity <= 4_096 {
			var processIDs = [pid_t](repeating: 0, count: capacity)
			let byteCount = processIDs.withUnsafeMutableBytes { buffer in
				proc_listchildpids(parentPID, buffer.baseAddress, Int32(buffer.count))
			}
			guard byteCount > 0 else {
				return []
			}
			let count = Int(byteCount) / MemoryLayout<pid_t>.stride
			if count < capacity {
				return processIDs.prefix(count).filter { $0 > 0 }
			}
			capacity *= 2
		}
		return []
	}

	private static func sessionProcessIDs(sessionID: pid_t) -> [pid_t] {
		let capacity = 8_192
		var processIDs = [pid_t](repeating: 0, count: capacity)
		let byteCount = processIDs.withUnsafeMutableBytes { buffer in
			proc_listpids(1, 0, buffer.baseAddress, Int32(buffer.count))
		}
		guard byteCount > 0 else {
			return []
		}
		let count = min(Int(byteCount) / MemoryLayout<pid_t>.stride, capacity)
		return processIDs.prefix(count).filter { processID in
			processID > 0 && getsid(processID) == sessionID
		}
	}

	private static func exitCode(fromWaitStatus status: Int32) -> Int32 {
		let signal = status & 0x7f
		if signal == 0 {
			return (status >> 8) & 0xff
		}
		if signal != 0x7f {
			return 128 + signal
		}
		return status
	}
}

enum ItsyTerminalEnvironment {
	private static let forwardedKeys: Set<String> = [
		"HOME",
		"LANG",
		"LC_ALL",
		"LC_COLLATE",
		"LC_CTYPE",
		"LC_MESSAGES",
		"LC_MONETARY",
		"LC_NUMERIC",
		"LC_TIME",
		"LOGNAME",
		"PATH",
		"SHELL",
		"SSH_AUTH_SOCK",
		"TMPDIR",
		"USER",
		"XDG_CONFIG_HOME",
		"XDG_DATA_HOME",
		"XDG_STATE_HOME",
	]

	static func build(from source: [String: String], shellPath: String) -> [String: String] {
		var environment: [String: String] = [:]
		for key in forwardedKeys {
			guard let value = source[key], !value.isEmpty else {
				continue
			}
			environment[key] = value
		}
		environment["PATH"] = environment["PATH"] ?? "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
		environment["SHELL"] = shellPath
		environment["INSIDE_ITSY_TERMINAL"] = "1"
		environment["TERM"] = "xterm-256color"
		environment["TERM_PROGRAM"] = "Itsy"
		environment["COLORTERM"] = "truecolor"
		environment["LC_CTYPE"] = environment["LC_CTYPE"] ?? localeFallback(from: source)
		return environment
	}

	private static func localeFallback(from source: [String: String]) -> String {
		if let lang = source["LANG"], !lang.isEmpty {
			return lang
		}
		return "UTF-8"
	}
}
