import Darwin
import Dispatch
import Foundation

final class ItsyTerminalSession {
	let currentDirectoryURL: URL
	private let shellURL: URL
	private let queue = DispatchQueue(label: "dev.itsy.terminal.session", qos: .userInitiated)
	private var masterFD: Int32 = -1
	private var childPID: pid_t = -1
	private var readSource: DispatchSourceRead?
	private var waitSource: DispatchSourceProcess?
	var onOutput: ((Data) -> Void)?
	var onExit: ((Int32) -> Void)?

	init(currentDirectoryURL: URL, environment: [String: String] = ProcessInfo.processInfo.environment) {
		self.currentDirectoryURL = currentDirectoryURL
		let shellPath = environment["SHELL"].flatMap { $0.isEmpty ? nil : $0 } ?? "/bin/zsh"
		shellURL = URL(fileURLWithPath: shellPath)
	}

	var isRunning: Bool {
		childPID > 0 && kill(childPID, 0) == 0
	}

	func start(columns: Int, rows: Int) throws {
		guard !isRunning else {
			resize(columns: columns, rows: rows)
			return
		}
		var master: Int32 = -1
		var size = winsize(
			ws_row: UInt16(max(1, rows)),
			ws_col: UInt16(max(1, columns)),
			ws_xpixel: 0,
			ws_ypixel: 0
		)
		let pid = forkpty(&master, nil, nil, &size)
		guard pid >= 0 else {
			throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
		}
		if pid == 0 {
			childMain()
		}
		masterFD = master
		childPID = pid
		startReadSource()
		startWaitSource(pid: pid)
	}

	func send(_ data: Data) {
		guard masterFD >= 0, !data.isEmpty else {
			return
		}
		data.withUnsafeBytes { bytes in
			guard let base = bytes.baseAddress else {
				return
			}
			var sent = 0
			while sent < data.count {
				let count = Darwin.write(masterFD, base.advanced(by: sent), data.count - sent)
				if count <= 0 {
					return
				}
				sent += count
			}
		}
	}

	func resize(columns: Int, rows: Int) {
		guard masterFD >= 0 else {
			return
		}
		var size = winsize(
			ws_row: UInt16(max(1, rows)),
			ws_col: UInt16(max(1, columns)),
			ws_xpixel: 0,
			ws_ypixel: 0
		)
		_ = ioctl(masterFD, TIOCSWINSZ, &size)
		if childPID > 0 {
			_ = kill(childPID, SIGWINCH)
		}
	}

	func terminate() {
		if childPID > 0 {
			_ = kill(childPID, SIGHUP)
		}
		closeMaster()
	}

	private func startReadSource() {
		let source = DispatchSource.makeReadSource(fileDescriptor: masterFD, queue: queue)
		source.setEventHandler { [weak self] in
			self?.readAvailableBytes()
		}
		source.setCancelHandler { [weak self] in
			self?.closeMaster()
		}
		readSource = source
		source.resume()
	}

	private func startWaitSource(pid: pid_t) {
		let source = DispatchSource.makeProcessSource(identifier: pid, eventMask: .exit, queue: queue)
		source.setEventHandler { [weak self] in
			var status: Int32 = 0
			_ = waitpid(pid, &status, WNOHANG)
			let code = Self.exitCode(fromWaitStatus: status)
			self?.childPID = -1
			self?.readSource?.cancel()
			self?.readSource = nil
			self?.onExit?(code)
		}
		waitSource = source
		source.resume()
	}

	private func readAvailableBytes() {
		var buffer = [UInt8](repeating: 0, count: 16384)
		while masterFD >= 0 {
			let count = Darwin.read(masterFD, &buffer, buffer.count)
			if count > 0 {
				onOutput?(Data(buffer.prefix(count)))
			} else {
				break
			}
		}
	}

	private func closeMaster() {
		if masterFD >= 0 {
			Darwin.close(masterFD)
			masterFD = -1
		}
	}

	private func childMain() -> Never {
		_ = currentDirectoryURL.path.withCString { chdir($0) }
		setenv("INSIDE_ITSY_TERMINAL", "1", 1)
		setenv("TERM", "xterm-256color", 1)
		setenv("COLORTERM", "truecolor", 1)
		setenv("LC_CTYPE", "UTF-8", 0)
		var argv: [UnsafeMutablePointer<CChar>?] = [strdup(shellURL.path), strdup("-il"), nil]
		let executable = argv[0]
		argv.withUnsafeMutableBufferPointer { buffer in
			if let executable {
				execv(executable, buffer.baseAddress)
			}
		}
		_exit(127)
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
