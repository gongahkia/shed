import AppKit
import Foundation
import ollyCore
import ollyDiagnostics
import ollyIPC
import ollyRuntime

@MainActor
final class SettingsErrorLogController {
    private let runtime: OllyRuntime?
    private let bundleWriter: DiagnosticBundleWriter
    private let textView = NSTextView()
    private var task: Task<Void, Never>?

    init(runtime: OllyRuntime?, bundleWriter: DiagnosticBundleWriter = DiagnosticBundleWriter()) {
        self.runtime = runtime
        self.bundleWriter = bundleWriter
    }

    deinit {
        task?.cancel()
    }

    func makeView() -> NSView {
        let root = NSStackView()
        root.orientation = .vertical
        root.spacing = 10
        root.edgeInsets = NSEdgeInsets(top: 14, left: 14, bottom: 14, right: 14)
        root.translatesAutoresizingMaskIntoConstraints = false

        let buttonRow = NSStackView()
        buttonRow.orientation = .horizontal
        buttonRow.spacing = 8
        buttonRow.addArrangedSubview(button("Refresh", #selector(refreshFromButton)))
        buttonRow.addArrangedSubview(button("Copy Diagnostic Bundle", #selector(copyDiagnosticBundle)))
        buttonRow.addArrangedSubview(NSView())

        textView.isEditable = false
        textView.isSelectable = true
        textView.font = .monospacedSystemFont(ofSize: 11, weight: .regular)
        textView.textColor = .secondaryLabelColor
        textView.drawsBackground = false

        let scrollView = NSScrollView()
        scrollView.documentView = textView
        scrollView.hasVerticalScroller = true
        scrollView.borderType = .bezelBorder

        root.addArrangedSubview(buttonRow)
        root.addArrangedSubview(scrollView)
        NSLayoutConstraint.activate([
            root.widthAnchor.constraint(greaterThanOrEqualToConstant: 560),
            scrollView.heightAnchor.constraint(equalToConstant: 300)
        ])
        start()
        return root
    }

    private func start() {
        guard task == nil else {
            return
        }
        refresh()
        guard let runtime else {
            return
        }
        task = Task { [weak self, runtime] in
            let stream = await runtime.runtimeEventBus.subscribe()
            for await event in stream where !Task.isCancelled {
                guard case .runtimeError = event else {
                    continue
                }
                self?.refresh()
            }
        }
    }

    @objc private func refreshFromButton() {
        refresh()
    }

    @objc private func copyDiagnosticBundle() {
        guard let runtime else {
            textView.string = "Runtime not available."
            return
        }
        textView.string = "Writing diagnostic bundle..."
        Task { [weak self, runtime] in
            let errors = await runtime.recentErrors()
            self?.writeBundle(errors: errors)
        }
    }

    private func refresh() {
        guard let runtime else {
            textView.string = "Runtime not available."
            return
        }
        Task { [weak self, runtime] in
            let errors = await runtime.recentErrors()
            self?.render(errors)
        }
    }

    private func render(_ errors: [RuntimeErrorRecord]) {
        guard !errors.isEmpty else {
            textView.textColor = .secondaryLabelColor
            textView.string = "No runtime errors recorded."
            return
        }
        textView.textColor = .labelColor
        textView.string = errors.map(Self.render).joined(separator: "\n\n")
    }

    private func writeBundle(errors: [RuntimeErrorRecord]) {
        do {
            let url = try bundleWriter.write(errors: errors)
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(url.path, forType: .string)
            textView.textColor = .labelColor
            textView.string = "Diagnostic bundle copied:\n\(url.path)"
        } catch {
            textView.textColor = .labelColor
            textView.string = "Diagnostic bundle failed:\n\(String(describing: error))"
        }
    }

    private static func render(_ record: RuntimeErrorRecord) -> String {
        "[\(iso8601.string(from: record.timestamp))] \(record.message)"
    }

    private func button(_ title: String, _ action: Selector) -> NSButton {
        let button = NSButton(title: title, target: self, action: action)
        button.bezelStyle = .rounded
        return button
    }
}

struct DiagnosticBundleWriter {
    let logDirectory: URL
    let recoveryJournalURL: URL
    let crashLogDirectory: URL
    let fileManager: FileManager
    var now: () -> Date

    init(
        logDirectory: URL = CrashTelemetry.defaultLogDirectory(),
        recoveryJournalURL: URL = WindowRecoveryJournal.defaultStateURL,
        crashLogDirectory: URL = CrashTelemetry.defaultLogDirectory(),
        fileManager: FileManager = .default,
        now: @escaping () -> Date = Date.init
    ) {
        self.logDirectory = logDirectory
        self.recoveryJournalURL = recoveryJournalURL
        self.crashLogDirectory = crashLogDirectory
        self.fileManager = fileManager
        self.now = now
    }

    func write(errors: [RuntimeErrorRecord]) throws -> URL {
        try fileManager.createDirectory(at: logDirectory, withIntermediateDirectories: true)
        let timestamp = filenameFormatter.string(from: now())
        let destinationURL = logDirectory.appendingPathComponent("\(timestamp)-diagnostic.zip")
        let stagingURL = fileManager.temporaryDirectory
            .appendingPathComponent("olly-diagnostic-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: stagingURL, withIntermediateDirectories: true)
        defer {
            try? fileManager.removeItem(at: stagingURL)
        }
        try writeRuntimeErrors(errors, to: stagingURL)
        try copyRecoveryJournal(to: stagingURL)
        try copyCrashReports(to: stagingURL)
        try archive(directory: stagingURL, to: destinationURL)
        return destinationURL
    }

    private func writeRuntimeErrors(_ errors: [RuntimeErrorRecord], to stagingURL: URL) throws {
        let url = stagingURL.appendingPathComponent("runtime-errors.json")
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(errors).write(to: url, options: .atomic)
    }

    private func copyRecoveryJournal(to stagingURL: URL) throws {
        guard fileManager.fileExists(atPath: recoveryJournalURL.path) else {
            return
        }
        try fileManager.copyItem(
            at: recoveryJournalURL,
            to: stagingURL.appendingPathComponent("recovery.json")
        )
    }

    private func copyCrashReports(to stagingURL: URL) throws {
        let reports = CrashTelemetry.pendingReportURLs(logDirectory: crashLogDirectory, fileManager: fileManager)
        guard !reports.isEmpty else {
            return
        }
        let target = stagingURL.appendingPathComponent("crash-reports", isDirectory: true)
        try fileManager.createDirectory(at: target, withIntermediateDirectories: true)
        for report in reports {
            try fileManager.copyItem(at: report, to: target.appendingPathComponent(report.lastPathComponent))
        }
    }

    private func archive(directory: URL, to destinationURL: URL) throws {
        if fileManager.fileExists(atPath: destinationURL.path) {
            try fileManager.removeItem(at: destinationURL)
        }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        process.currentDirectoryURL = directory
        process.arguments = ["-qr", destinationURL.path, "."]
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw DiagnosticBundleError.archiveFailed(process.terminationStatus)
        }
    }
}

enum DiagnosticBundleError: Error, Equatable {
    case archiveFailed(Int32)
}

private let iso8601 = ISO8601DateFormatter()

private let filenameFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyyMMdd-HHmmss"
    return formatter
}()
