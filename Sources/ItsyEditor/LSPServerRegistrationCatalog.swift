import Foundation

public struct LSPServerRegistration: Equatable, Sendable {
	public var languageID: String
	public var grammarIDs: [String]
	public var config: LSPServerConfig

	public init(languageID: String, grammarIDs: [String], config: LSPServerConfig) {
		self.languageID = languageID
		self.grammarIDs = grammarIDs
		self.config = config
	}
}

public enum LSPServerRegistrationCatalogError: Error, Equatable, Sendable {
	case missingServer(String)
	case conflictingServerConfig(languageID: String)
}

public enum LSPServerRegistrationCatalog {
	public static let bundled = try! build(from: BundledLanguageInventory.languages)

	public static func build(from languages: [BundledLanguage]) throws -> [LSPServerRegistration] {
		var languageIDs: [String] = []
		var languagesByID: [String: [BundledLanguage]] = [:]
		for language in languages {
			guard case .supported = language.support else {
				continue
			}
			guard language.server != nil else {
				throw LSPServerRegistrationCatalogError.missingServer(language.grammarID)
			}
			if languagesByID[language.languageID] == nil {
				languageIDs.append(language.languageID)
			}
			languagesByID[language.languageID, default: []].append(language)
		}
		return try languageIDs.map { languageID in
			let grouped = languagesByID[languageID] ?? []
			guard let server = grouped.first?.server else {
				throw LSPServerRegistrationCatalogError.missingServer(languageID)
			}
			let config = server.config(languageID: languageID)
			guard grouped.dropFirst().allSatisfy({ $0.server?.config(languageID: languageID) == config }) else {
				throw LSPServerRegistrationCatalogError.conflictingServerConfig(languageID: languageID)
			}
			return LSPServerRegistration(
				languageID: languageID,
				grammarIDs: grouped.map(\.grammarID).sorted(),
				config: config
			)
		}
	}
}
