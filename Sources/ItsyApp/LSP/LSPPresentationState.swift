import Foundation
import ItsyEditor
import ItsyLSP

struct LSPStatusEntry {
	var key: LSPSessionKey
	var status: String
	var health: LSPHealthState
	var server: String
	var pid: Int32?
	var startDate: Date?
	var lastError: String
	var output: [LSPSessionOutput]
	var url: URL?
	var client: LSPProcessClient?
}

@MainActor final class LSPPresentationState {
	var entries: [LSPSessionKey: LSPStatusEntry] = [:]
	var missingBannerGeneration = 0
	var bannerDocumentURL: URL?
	var statusGeneration = 0
	var configurationGeneration = 0
	var indexingStatusText: String?
	var crashStatusText: String?
	var restartKey: LSPSessionKey?
	var restartURL: URL?
	var activeKey: LSPSessionKey?
	var statusPanel: LSPStatusPanel?

	func setStatus(
		key: LSPSessionKey,
		status: String,
		client: LSPProcessClient?,
		lastError: String?,
		url: URL?,
		server: String? = nil,
		health: LSPHealthState? = nil
	) -> LSPStatusEntry {
		let existing = entries[key]
		let clearsClient = status == "idle" || status == "crashed" || status == "disabled" || status == "unavailable"
		let resolvedHealth = health ?? (status == "running" && existing?.health == .degraded ? .degraded : Self.health(for: status))
		let entry = LSPStatusEntry(
			key: key,
			status: status,
			health: resolvedHealth,
			server: server ?? client.map(Self.serverName(for:)) ?? existing?.server ?? key.languageID,
			pid: clearsClient ? nil : client?.processIdentifier ?? existing?.pid,
			startDate: clearsClient ? nil : client?.startDate ?? existing?.startDate,
			lastError: lastError ?? existing?.lastError ?? "",
			output: existing?.output ?? [],
			url: url ?? existing?.url,
			client: client ?? (clearsClient ? nil : existing?.client)
		)
		entries[key] = entry
		activeKey = key
		if status == "crashed" || status == "disabled" {
			restartKey = key
			restartURL = url ?? existing?.url
		}
		return entry
	}

	@discardableResult func append(_ output: LSPSessionOutput, for key: LSPSessionKey) -> Bool {
		guard var entry = entries[key] else {
			return false
		}
		entry.output.append(output)
		if entry.output.count > 200 {
			entry.output.removeFirst(entry.output.count - 200)
		}
		if output.kind == .protocolOutput {
			entry.status = "degraded"
			entry.health = .degraded
			entry.lastError = output.text
		}
		entries[key] = entry
		activeKey = key
		return true
	}

	func snapshot() -> LSPStatusPanelSnapshot? {
		guard let key = activeKey, let entry = entries[key] else {
			return nil
		}
		return LSPStatusPanelSnapshot(
			key: entry.key,
			status: entry.status,
			health: entry.health,
			server: entry.server,
			pid: entry.pid,
			startDate: entry.startDate,
			lastError: entry.lastError,
			output: entry.output
		)
	}

	func resetForConfigurationReload() {
		configurationGeneration &+= 1
		entries.removeAll()
		activeKey = nil
		restartKey = nil
		restartURL = nil
		crashStatusText = nil
	}

	var statusText: String? {
		crashStatusText ?? indexingStatusText
	}

	private static func health(for status: String) -> LSPHealthState {
		switch status {
		case "starting": .starting
		case "running", "ready": .ready
		case "degraded", "disabled": .degraded
		case "crashed": .crashed
		case "unavailable": .unavailable
		default: .idle
		}
	}

	private static func serverName(for client: LSPProcessClient) -> String {
		([client.executableURL.path] + client.arguments).joined(separator: " ")
	}
}
