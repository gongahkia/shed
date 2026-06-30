import AppKit
import Foundation
import ollyDSL

extension SettingsWindowController {
    @objc func exportConfig() {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "Config.swift"
        panel.canCreateDirectories = true
        panel.message = L10n.s("Export the current Olly Config.swift.", "config export panel message")
        let profile = selectedProfile()
        guard let window else {
            finishExport(response: panel.runModal(), url: panel.url, profile: profile)
            return
        }
        panel.beginSheetModal(for: window) { [weak self, panel] response in
            self?.finishExport(response: response, url: panel.url, profile: profile)
        }
    }

    @objc func importConfig() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = false
        panel.message = L10n.s("Import an Olly Config.swift.", "config import panel message")
        guard let window else {
            finishImport(response: panel.runModal(), url: panel.url)
            return
        }
        panel.beginSheetModal(for: window) { [weak self, panel] response in
            self?.finishImport(response: response, url: panel.url)
        }
    }

    static func exportConfig(
        profile: ConfigTemplateProfile,
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        destinationURL: URL,
        fileManager: FileManager = .default
    ) throws {
        try ensureConfigExists(profile: profile, sourceURL: sourceURL, fileManager: fileManager)
        try writeContents(from: sourceURL, to: destinationURL, fileManager: fileManager)
    }

    static func importConfig(
        from importURL: URL,
        to sourceURL: URL = ConfigLoader.defaultSourceURL(),
        fileManager: FileManager = .default
    ) throws {
        try writeContents(from: importURL, to: sourceURL, fileManager: fileManager)
    }

    private func finishExport(
        response: NSApplication.ModalResponse,
        url: URL?,
        profile: ConfigTemplateProfile
    ) {
        guard response == .OK, let url else { return }
        do {
            try Self.exportConfig(
                profile: profile,
                sourceURL: sourceURL,
                destinationURL: url,
                fileManager: fileManager
            )
            statusLabel.stringValue = L10n.s("Exported Config.swift", "config export success status")
            errorTextView.string = ""
            refreshCreateConfigButton()
        } catch {
            showError(error)
        }
    }

    private func finishImport(response: NSApplication.ModalResponse, url: URL?) {
        guard response == .OK, let url else { return }
        do {
            try Self.importConfig(from: url, to: sourceURL, fileManager: fileManager)
            statusLabel.stringValue = L10n.s("Imported Config.swift", "config import success status")
            errorTextView.string = ""
            refreshCreateConfigButton()
        } catch {
            showError(error)
        }
    }

    private static func writeContents(from sourceURL: URL, to destinationURL: URL, fileManager: FileManager) throws {
        let sourcePath = sourceURL.standardizedFileURL.path
        let destinationPath = destinationURL.standardizedFileURL.path
        guard sourcePath != destinationPath else {
            return
        }
        let data = try Data(contentsOf: sourceURL)
        try fileManager.createDirectory(
            at: destinationURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try data.write(to: destinationURL, options: .atomic)
    }
}
