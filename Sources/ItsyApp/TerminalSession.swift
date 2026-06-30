import Foundation

final class ItsyTerminalSession {
	let currentDirectoryURL: URL
	private let shellURL: URL
	private var process: Process?
	private var inputPipe: Pipe?
	private var outputPipe: Pipe?
	private var errorPipe: Pipe?
	var onOutput: ((String) -> Void)?
	var onExit: ((Int32) -> Void)?

	init(currentDirectoryURL: URL, environment: [String: String] = ProcessInfo.processInfo.environment) {
		self.currentDirectoryURL = currentDirectoryURL
		let shellPath = environment["SHELL"].flatMap { $0.isEmpty ? nil : $0 } ?? "/bin/zsh"
		shellURL = URL(fileURLWithPath: shellPath)
	}

	var isRunning: Bool {
		process?.isRunning == true
	}

	func start() throws {
		guard process == nil || process?.isRunning == false else {
			return
		}
		let inputPipe = Pipe()
		let outputPipe = Pipe()
		let errorPipe = Pipe()
		let process = Process()
		process.executableURL = shellURL
		process.arguments = ["-il"]
		process.currentDirectoryURL = currentDirectoryURL
		process.standardInput = inputPipe
		process.standardOutput = outputPipe
		process.standardError = errorPipe
		process.environment = ProcessInfo.processInfo.environment.merging([
			"INSIDE_ITSY_TERMINAL": "1",
			"TERM": "dumb",
		]) { _, new in new }
		outputPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
			self?.emit(handle.availableData)
		}
		errorPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
			self?.emit(handle.availableData)
		}
		process.terminationHandler = { [weak self] process in
			self?.cleanupPipes()
			self?.onExit?(process.terminationStatus)
		}
		self.inputPipe = inputPipe
		self.outputPipe = outputPipe
		self.errorPipe = errorPipe
		self.process = process
		try process.run()
	}

	func sendLine(_ line: String) {
		guard let inputPipe, isRunning else {
			return
		}
		inputPipe.fileHandleForWriting.write(Data((line + "\n").utf8))
	}

	func terminate() {
		guard let process else {
			cleanupPipes()
			return
		}
		if process.isRunning {
			process.terminate()
		}
		cleanupPipes()
	}

	private func emit(_ data: Data) {
		guard !data.isEmpty else {
			return
		}
		onOutput?(String(decoding: data, as: UTF8.self))
	}

	private func cleanupPipes() {
		outputPipe?.fileHandleForReading.readabilityHandler = nil
		errorPipe?.fileHandleForReading.readabilityHandler = nil
		inputPipe = nil
		outputPipe = nil
		errorPipe = nil
	}
}
