import Foundation

public struct LuaPluginVersion: Comparable, Equatable, Sendable {
	public let major: Int
	public let minor: Int
	public let patch: Int

	public init(major: Int, minor: Int, patch: Int) {
		self.major = major
		self.minor = minor
		self.patch = patch
	}

	public init(parsing value: String) throws {
		let components = value.split(separator: ".", omittingEmptySubsequences: false)
		guard components.count == 3,
		      let major = Int(components[0]), major >= 0,
		      let minor = Int(components[1]), minor >= 0,
		      let patch = Int(components[2]), patch >= 0
		else {
			throw LuaPluginManifestError.invalidPackageVersion(value)
		}
		self.init(major: major, minor: minor, patch: patch)
	}

	public static func < (lhs: LuaPluginVersion, rhs: LuaPluginVersion) -> Bool {
		(lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
	}
}

public struct LuaPluginAPIRange: Equatable, Sendable {
	public enum Operator: String, Equatable, Sendable {
		case equal = "="
		case greaterThan = ">"
		case greaterThanOrEqual = ">="
		case lessThan = "<"
		case lessThanOrEqual = "<="
	}

	public struct Constraint: Equatable, Sendable {
		public let `operator`: Operator
		public let version: LuaPluginVersion

		public init(operator: Operator, version: LuaPluginVersion) {
			self.operator = `operator`
			self.version = version
		}
	}

	public let constraints: [Constraint]

	public init(parsing value: String) throws {
		let tokens = value.split(whereSeparator: \.isWhitespace)
		guard !tokens.isEmpty else {
			throw LuaPluginManifestError.invalidAPIRange(value)
		}
		constraints = try tokens.map { token in
			let value = String(token)
			let operatorAndVersion: (Operator, String)
			if value.hasPrefix(">=") {
				operatorAndVersion = (.greaterThanOrEqual, String(value.dropFirst(2)))
			} else if value.hasPrefix("<=") {
				operatorAndVersion = (.lessThanOrEqual, String(value.dropFirst(2)))
			} else if value.hasPrefix(">") {
				operatorAndVersion = (.greaterThan, String(value.dropFirst()))
			} else if value.hasPrefix("<") {
				operatorAndVersion = (.lessThan, String(value.dropFirst()))
			} else if value.hasPrefix("=") {
				operatorAndVersion = (.equal, String(value.dropFirst()))
			} else {
				operatorAndVersion = (.equal, value)
			}
			guard !operatorAndVersion.1.isEmpty else {
				throw LuaPluginManifestError.invalidAPIRange(value)
			}
			do {
				return Constraint(operator: operatorAndVersion.0, version: try LuaPluginVersion(parsing: operatorAndVersion.1))
			} catch {
				throw LuaPluginManifestError.invalidAPIRange(value)
			}
		}
	}

	public func contains(_ version: LuaPluginVersion) -> Bool {
		constraints.allSatisfy { constraint in
			switch constraint.operator {
			case .equal:
				version == constraint.version
			case .greaterThan:
				version > constraint.version
			case .greaterThanOrEqual:
				version >= constraint.version
			case .lessThan:
				version < constraint.version
			case .lessThanOrEqual:
				version <= constraint.version
			}
		}
	}
}

public struct LuaPluginManifest: Equatable, Sendable {
	public static let currentManifestVersion = 1
	public static let currentAPIVersion = LuaPluginVersion(major: 1, minor: 0, patch: 0)

	public let manifestVersion: Int
	public let identifier: String
	public let version: LuaPluginVersion
	public let apiRange: LuaPluginAPIRange
	public let entrypoint: String
	public let packageRoot: URL
	public let manifestURL: URL
	public let entrypointURL: URL

	public init(
		manifestVersion: Int,
		identifier: String,
		version: LuaPluginVersion,
		apiRange: LuaPluginAPIRange,
		entrypoint: String,
		packageRoot: URL,
		manifestURL: URL,
		entrypointURL: URL
	) {
		self.manifestVersion = manifestVersion
		self.identifier = identifier
		self.version = version
		self.apiRange = apiRange
		self.entrypoint = entrypoint
		self.packageRoot = packageRoot.standardizedFileURL
		self.manifestURL = manifestURL.standardizedFileURL
		self.entrypointURL = entrypointURL.standardizedFileURL
	}
}

public enum LuaPluginManifestError: Error, Equatable, Sendable {
	case manifestNotFound
	case invalidSyntax
	case duplicateField(String)
	case missingField(String)
	case invalidField(String)
	case unsupportedManifestVersion(Int)
	case invalidIdentifier(String)
	case invalidPackageVersion(String)
	case invalidAPIRange(String)
	case incompatibleAPIRange(String)
	case invalidEntrypoint(String)
	case absoluteEntrypoint(String)
	case parentDirectoryEntrypoint(String)
	case missingEntrypoint(String)
	case nonFileEntrypoint(String)
	case symbolicLinkEntrypoint(String)
}

public enum LuaPluginManifestLoader {
	public static let manifestFilename = "itsy.lua"

	public static func load(packageRoot: URL, fileManager: FileManager = .default) throws -> LuaPluginManifest {
		let packageRoot = packageRoot.standardizedFileURL
		let manifestURL = packageRoot.appendingPathComponent(manifestFilename)
		guard fileManager.fileExists(atPath: manifestURL.path) else {
			throw LuaPluginManifestError.manifestNotFound
		}
		guard let source = try? String(contentsOf: manifestURL, encoding: .utf8) else {
			throw LuaPluginManifestError.invalidSyntax
		}
		var parser = LuaManifestLiteralParser(source: source)
		let fields = try parser.parse()
		guard let manifestField = fields["manifest_version"] else {
			throw LuaPluginManifestError.missingField("manifest_version")
		}
		guard case let .integer(manifestVersion) = manifestField else {
			throw LuaPluginManifestError.invalidField("manifest_version")
		}
		guard manifestVersion == LuaPluginManifest.currentManifestVersion else {
			throw LuaPluginManifestError.unsupportedManifestVersion(manifestVersion)
		}
		let identifier = try requiredString("id", fields: fields)
		guard isValidIdentifier(identifier) else {
			throw LuaPluginManifestError.invalidIdentifier(identifier)
		}
		let versionText = try requiredString("version", fields: fields)
		let version = try LuaPluginVersion(parsing: versionText)
		let apiText = try requiredString("api", fields: fields)
		let apiRange = try LuaPluginAPIRange(parsing: apiText)
		guard apiRange.contains(LuaPluginManifest.currentAPIVersion) else {
			throw LuaPluginManifestError.incompatibleAPIRange(apiText)
		}
		let entrypoint = try requiredString("entrypoint", fields: fields)
		let entrypointURL = try resolveEntrypoint(entrypoint, packageRoot: packageRoot, fileManager: fileManager)
		return LuaPluginManifest(
			manifestVersion: manifestVersion,
			identifier: identifier,
			version: version,
			apiRange: apiRange,
			entrypoint: entrypoint,
			packageRoot: packageRoot,
			manifestURL: manifestURL,
			entrypointURL: entrypointURL
		)
	}

	private static func requiredString(_ field: String, fields: [String: LuaManifestValue]) throws -> String {
		guard let value = fields[field] else {
			throw LuaPluginManifestError.missingField(field)
		}
		guard case let .string(string) = value, !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
			throw LuaPluginManifestError.invalidField(field)
		}
		return string
	}

	private static func isValidIdentifier(_ value: String) -> Bool {
		guard let first = value.unicodeScalars.first,
		      isIdentifierAlphanumeric(first),
		      let last = value.unicodeScalars.last,
		      isIdentifierAlphanumeric(last)
		else {
			return false
		}
		return value.unicodeScalars.allSatisfy { isIdentifierAlphanumeric($0) || $0 == "." || $0 == "-" || $0 == "_" }
	}

	private static func isIdentifierAlphanumeric(_ scalar: UnicodeScalar) -> Bool {
		(48...57).contains(scalar.value) || (65...90).contains(scalar.value) || (97...122).contains(scalar.value)
	}

	private static func resolveEntrypoint(_ value: String, packageRoot: URL, fileManager: FileManager) throws -> URL {
		guard !value.hasPrefix("/"), !value.hasPrefix("file:") else {
			throw LuaPluginManifestError.absoluteEntrypoint(value)
		}
		let components = value.split(separator: "/", omittingEmptySubsequences: false)
		guard !components.contains(".."), !components.contains("") else {
			throw LuaPluginManifestError.parentDirectoryEntrypoint(value)
		}
		guard value.lowercased().hasSuffix(".lua") else {
			throw LuaPluginManifestError.invalidEntrypoint(value)
		}
		let entrypointURL = packageRoot.appendingPathComponent(value).standardizedFileURL
		guard fileManager.fileExists(atPath: entrypointURL.path) else {
			throw LuaPluginManifestError.missingEntrypoint(value)
		}
		let values = try entrypointURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
		guard values.isSymbolicLink != true else {
			throw LuaPluginManifestError.symbolicLinkEntrypoint(value)
		}
		guard values.isRegularFile == true else {
			throw LuaPluginManifestError.nonFileEntrypoint(value)
		}
		return entrypointURL
	}
}

private enum LuaManifestValue: Equatable {
	case string(String)
	case integer(Int)
}

private struct LuaManifestLiteralParser {
	private let scalars: [UnicodeScalar]
	private var index = 0

	init(source: String) {
		scalars = Array(source.unicodeScalars)
	}

	mutating func parse() throws -> [String: LuaManifestValue] {
		try skipTrivia()
		guard try parseIdentifier() == "return" else {
			throw LuaPluginManifestError.invalidSyntax
		}
		try skipTrivia()
		try require("{")
		var fields: [String: LuaManifestValue] = [:]
		while true {
			try skipTrivia()
			if consume("}") {
				break
			}
			let key = try parseIdentifier()
			try skipTrivia()
			try require("=")
			try skipTrivia()
			let value = try parseValue()
			guard fields.updateValue(value, forKey: key) == nil else {
				throw LuaPluginManifestError.duplicateField(key)
			}
			try skipTrivia()
			if consume(",") || consume(";") {
				continue
			}
			guard peek == "}" else {
				throw LuaPluginManifestError.invalidSyntax
			}
		}
		try skipTrivia()
		guard index == scalars.count else {
			throw LuaPluginManifestError.invalidSyntax
		}
		return fields
	}

	private mutating func parseValue() throws -> LuaManifestValue {
		guard let scalar = peek else {
			throw LuaPluginManifestError.invalidSyntax
		}
		if scalar == "\"" || scalar == "'" {
			return .string(try parseString())
		}
		guard isDigit(scalar) else {
			throw LuaPluginManifestError.invalidSyntax
		}
		let start = index
		while let scalar = peek, isDigit(scalar) {
			index += 1
		}
		guard let value = Int(String(String.UnicodeScalarView(scalars[start ..< index]))) else {
			throw LuaPluginManifestError.invalidSyntax
		}
		return .integer(value)
	}

	private mutating func parseString() throws -> String {
		guard let quote = peek else {
			throw LuaPluginManifestError.invalidSyntax
		}
		index += 1
		var value = ""
		while let scalar = peek {
			index += 1
			if scalar == quote {
				return value
			}
			if scalar == "\\" {
				guard let escaped = peek else {
					throw LuaPluginManifestError.invalidSyntax
				}
				index += 1
				switch escaped {
				case "n": value.append("\n")
				case "r": value.append("\r")
				case "t": value.append("\t")
				case "\\", "\"", "'": value.unicodeScalars.append(escaped)
				default: throw LuaPluginManifestError.invalidSyntax
				}
				continue
			}
			guard scalar != "\n", scalar != "\r" else {
				throw LuaPluginManifestError.invalidSyntax
			}
			value.unicodeScalars.append(scalar)
		}
		throw LuaPluginManifestError.invalidSyntax
	}

	private mutating func parseIdentifier() throws -> String {
		guard let scalar = peek, isIdentifierStart(scalar) else {
			throw LuaPluginManifestError.invalidSyntax
		}
		let start = index
		index += 1
		while let scalar = peek, isIdentifierContinue(scalar) {
			index += 1
		}
		return String(String.UnicodeScalarView(scalars[start ..< index]))
	}

	private mutating func skipTrivia() throws {
		while true {
			while let scalar = peek, CharacterSet.whitespacesAndNewlines.contains(scalar) {
				index += 1
			}
			guard peek == "-", peek(offset: 1) == "-" else {
				return
			}
			index += 2
			if peek == "[", peek(offset: 1) == "[" {
				index += 2
				while index + 1 < scalars.count, !(peek == "]" && peek(offset: 1) == "]") {
					index += 1
				}
				guard index + 1 < scalars.count else {
					throw LuaPluginManifestError.invalidSyntax
				}
				index += 2
			} else {
				while let scalar = peek, scalar != "\n", scalar != "\r" {
					index += 1
				}
			}
		}
	}

	private var peek: UnicodeScalar? {
		index < scalars.count ? scalars[index] : nil
	}

	private func peek(offset: Int) -> UnicodeScalar? {
		let index = index + offset
		return index < scalars.count ? scalars[index] : nil
	}

	private mutating func require(_ scalar: UnicodeScalar) throws {
		guard consume(scalar) else {
			throw LuaPluginManifestError.invalidSyntax
		}
	}

	private mutating func consume(_ scalar: UnicodeScalar) -> Bool {
		guard peek == scalar else {
			return false
		}
		index += 1
		return true
	}

	private func isDigit(_ scalar: UnicodeScalar) -> Bool {
		(48...57).contains(scalar.value)
	}

	private func isIdentifierStart(_ scalar: UnicodeScalar) -> Bool {
		scalar == "_" || (65...90).contains(scalar.value) || (97...122).contains(scalar.value)
	}

	private func isIdentifierContinue(_ scalar: UnicodeScalar) -> Bool {
		isIdentifierStart(scalar) || isDigit(scalar)
	}
}
