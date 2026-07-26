import Foundation

public struct ItsySettingsJSONDocument: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var settings: ItsySettings

	public init(schemaVersion: Int = ItsySettingsSchema.currentVersion, settings: ItsySettings) {
		self.schemaVersion = schemaVersion
		self.settings = settings
	}
}

public enum ItsySettingsJSONCodecError: Error, Equatable, Sendable {
	case unsupportedSchemaVersion(Int)
	case invalidSettings
}

public enum ItsySettingsJSONCodec {
	public static func encode(_ settings: ItsySettings) throws -> Data {
		let encoder = JSONEncoder()
		encoder.keyEncodingStrategy = .convertToSnakeCase
		encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
		return try encoder.encode(ItsySettingsJSONDocument(settings: settings.normalized()))
	}

	public static func decode(_ data: Data) throws -> ItsySettings {
		let decoder = JSONDecoder()
		decoder.keyDecodingStrategy = .convertFromSnakeCase
		let document = try decoder.decode(ItsySettingsJSONDocument.self, from: data)
		guard document.schemaVersion == ItsySettingsSchema.currentVersion else {
			throw ItsySettingsJSONCodecError.unsupportedSchemaVersion(document.schemaVersion)
		}
		guard document.settings == document.settings.normalized() else {
			throw ItsySettingsJSONCodecError.invalidSettings
		}
		return document.settings
	}
}
