import Darwin
import Foundation
import ollyDSL
import ollyIPC

public actor RawActionExecutor {
    private static let defaultTimeoutMs = 5_000
    private static let outputHeadLimit = 4_096
    private static let coalesceInterval: TimeInterval = 0.25

    private let now: @Sendable () -> Date
    private var lastRunByLabel: [String: Date] = [:]
    private var didWarnShellExecOff = false

    public init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    public func run(
        _ action: ShellAction,
        policy: ShellExecPolicy,
        environment: [String: String] = [:]
    ) async -> IPCRawActionEvent {
        guard policy.allows(label: action.label) else {
            warnShellExecOffIfNeeded(policy)
            return denied(action.label, reason: "not allowed")
        }
        let startDate = now()
        if let lastRun = lastRunByLabel[action.label],
           startDate.timeIntervalSince(lastRun) < Self.coalesceInterval {
            return denied(action.label, reason: "coalesced")
        }
        lastRunByLabel[action.label] = startDate
        return await execute(action, environment: environment)
    }

    private func execute(_ action: ShellAction, environment: [String: String]) async -> IPCRawActionEvent {
        let started = DispatchTime.now().uptimeNanoseconds
        let process = Process()
        let stdout = Pipe()
        let stderr = Pipe()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", action.command]
        let cwd = action.cwd ?? FileManager.default.homeDirectoryForCurrentUser.path
        process.currentDirectoryURL = URL(fileURLWithPath: cwd)
        process.environment = ProcessInfo.processInfo.environment.merging(environment) { _, injected in injected }
        process.standardOutput = stdout
        process.standardError = stderr

        do {
            try process.run()
        } catch {
            return IPCRawActionEvent(
                label: action.label,
                status: .failed,
                stderrHead: String(describing: error),
                elapsedMs: elapsedMilliseconds(since: started)
            )
        }

        let timedOut = await waitForProcess(process, timeoutMs: action.timeoutMs ?? Self.defaultTimeoutMs)
        let stdoutData = stdout.fileHandleForReading.readDataToEndOfFile()
        let stderrData = stderr.fileHandleForReading.readDataToEndOfFile()
        return IPCRawActionEvent(
            label: action.label,
            status: timedOut ? .timedOut : .completed,
            exit: process.terminationStatus,
            stdoutHead: stdoutData.utf8Head(limit: Self.outputHeadLimit),
            stderrHead: stderrData.utf8Head(limit: Self.outputHeadLimit),
            elapsedMs: elapsedMilliseconds(since: started)
        )
    }

    private func waitForProcess(_ process: Process, timeoutMs: Int) async -> Bool {
        let timeoutNanoseconds = UInt64(max(timeoutMs, 1)) * 1_000_000
        let deadline = DispatchTime.now().uptimeNanoseconds + timeoutNanoseconds
        while process.isRunning {
            if DispatchTime.now().uptimeNanoseconds >= deadline {
                await terminateTimedOutProcess(process)
                return true
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return false
    }

    private func terminateTimedOutProcess(_ process: Process) async {
        process.terminate()
        let graceDeadline = DispatchTime.now().uptimeNanoseconds + 1_000_000_000
        while process.isRunning, DispatchTime.now().uptimeNanoseconds < graceDeadline {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        if process.isRunning {
            kill(process.processIdentifier, SIGKILL)
            while process.isRunning {
                try? await Task.sleep(nanoseconds: 10_000_000)
            }
        }
    }

    private func warnShellExecOffIfNeeded(_ policy: ShellExecPolicy) {
        guard policy == .off, !didWarnShellExecOff else {
            return
        }
        didWarnShellExecOff = true
        FileHandle.standardError.write(Data("olly: shell exec disabled by Permissions.shellExec(.off)\n".utf8))
    }

    private func denied(_ label: String, reason: String) -> IPCRawActionEvent {
        IPCRawActionEvent(label: label, status: .denied, stderrHead: reason)
    }

    private func elapsedMilliseconds(since started: UInt64) -> Int {
        let elapsedNanoseconds = DispatchTime.now().uptimeNanoseconds - started
        return Int(elapsedNanoseconds / 1_000_000)
    }
}

private extension Data {
    func utf8Head(limit: Int) -> String {
        let bytes = Array(prefix(limit))
        return String(bytes: bytes, encoding: .utf8) ?? ""
    }
}
