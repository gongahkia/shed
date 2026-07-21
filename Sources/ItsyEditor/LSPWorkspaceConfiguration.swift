import Foundation
import ItsyLSP

public enum LSPWorkspaceConfigurationHandler {
	public static func respond(to params: LSPConfigurationParams, using config: LSPServerConfig) -> [LSPAny] {
		params.items.map { item in
			lookupSection(item.section, in: config.settings)
		}
	}

	public static func respondLSPAny(to params: LSPConfigurationParams, using config: LSPServerConfig) -> LSPAny {
		.array(respond(to: params, using: config))
	}

	private static func lookupSection(_ section: String?, in settings: [String: LSPConfigurationValue]) -> LSPAny {
		guard let section, !section.isEmpty else {
			return .null
		}
		if let direct = settings[section] {
			return direct.lspAny
		}
		let prefix = section + "."
		let nested = settings
			.filter { $0.key.hasPrefix(prefix) }
			.reduce(into: [String: LSPAny]()) { result, entry in
				let suffix = String(entry.key.dropFirst(prefix.count))
			result[suffix] = entry.value.lspAny
			}
		return nested.isEmpty ? .null : .object(nested)
	}
}
