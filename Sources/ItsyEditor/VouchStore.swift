import Foundation

public enum VouchPackageKind: String, Equatable, Sendable {
	case extensionPackage = "extension"
	case luaPlugin = "lua-plugin"
}

public enum VouchPackageScope: String, Equatable, Sendable {
	case global
	case workspace
}

public enum LuaPluginCapability: String, CaseIterable, Equatable, Hashable, Sendable {
	case filesystem
	case process
	case network

	static let vouchGrantable: Set<LuaPluginCapability> = [.process, .network]
}

public struct VouchSubject: Equatable, Sendable {
	public var sha256: String
	public var identifier: String
	public var version: String
	public var packageKind: VouchPackageKind
	public var packageScope: VouchPackageScope?

	public init(
		sha256: String,
		identifier: String,
		version: String,
		packageKind: VouchPackageKind = .extensionPackage,
		packageScope: VouchPackageScope? = nil
	) {
		self.sha256 = sha256.lowercased()
		self.identifier = identifier
		self.version = version
		self.packageKind = packageKind
		self.packageScope = packageScope
	}
}

public enum VouchDirective: Equatable, Sendable {
	case allow
	case deny
}

public struct VouchRecord: Equatable, Sendable {
	public var directive: VouchDirective
	public var sha256: String
	public var identifier: String
	public var version: String?
	public var packageKind: VouchPackageKind?
	public var packageScope: VouchPackageScope?
	public var capabilities: Set<LuaPluginCapability>
	public var signer: String?
	public var reason: String?
	public var source: URL?
	public var line: Int

	public init(
		directive: VouchDirective,
		sha256: String,
		identifier: String,
		version: String? = nil,
		packageKind: VouchPackageKind? = nil,
		packageScope: VouchPackageScope? = nil,
		capabilities: Set<LuaPluginCapability> = [],
		signer: String? = nil,
		reason: String? = nil,
		source: URL? = nil,
		line: Int = 0
	) {
		self.directive = directive
		self.sha256 = sha256.lowercased()
		self.identifier = identifier
		self.version = version
		self.packageKind = packageKind
		self.packageScope = packageScope
		self.capabilities = capabilities
		self.signer = signer
		self.reason = reason
		self.source = source
		self.line = line
	}
}

public enum VouchDecision: Equatable, Sendable {
	case allow(VouchRecord)
	case deny(VouchRecord)
	case missing
}

public enum VouchParseError: Error, Equatable, Sendable {
	case unknownDirective(line: Int, value: String)
	case malformedField(line: Int, value: String)
	case missingField(line: Int, field: String)
	case duplicateField(line: Int, field: String)
	case invalidSHA256(line: Int, value: String)
	case invalidPackageKind(line: Int, value: String)
	case invalidPackageScope(line: Int, value: String)
	case invalidCapability(line: Int, value: String)
	case duplicateCapability(line: Int, value: String)
	case capabilitiesRequireLuaPlugin(line: Int)
	case capabilitiesRequireAllow(line: Int)
	case scopeRequiresLuaPlugin(line: Int)
	case luaPluginScopeRequired(line: Int)
}

public struct VouchStore: Equatable, Sendable {
	public var records: [VouchRecord]

	public init(records: [VouchRecord] = []) {
		self.records = records
	}

	public static func parse(_ text: String, source: URL? = nil) throws -> VouchStore {
		var records: [VouchRecord] = []
		for (offset, rawLine) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
			let lineNumber = offset + 1
			let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
			guard !line.isEmpty, !line.hasPrefix("#") else {
				continue
			}
			records.append(try parseRecord(line, source: source, line: lineNumber))
		}
		return VouchStore(records: records)
	}

	public static func load(urls: [URL], fileManager: FileManager = .default) throws -> VouchStore {
		var records: [VouchRecord] = []
		for url in urls {
			guard fileManager.fileExists(atPath: url.path) else {
				continue
			}
			let store = try parse(String(contentsOf: url, encoding: .utf8), source: url)
			records.append(contentsOf: store.records)
		}
		return VouchStore(records: records)
	}

	public static func defaultURLs(repoRoot: URL, workspaceRoot: URL, homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> [URL] {
		[
			repoRoot.appendingPathComponent("VOUCHED"),
			homeDirectory
				.appendingPathComponent(".config", isDirectory: true)
				.appendingPathComponent("itsy", isDirectory: true)
				.appendingPathComponent("VOUCHED"),
			workspaceRoot
				.appendingPathComponent(".itsy", isDirectory: true)
				.appendingPathComponent("VOUCHED"),
		]
	}

	public func decision(for subject: VouchSubject) -> VouchDecision {
		let matching = records.filter { record in
			record.sha256 == subject.sha256
				&& record.identifier == subject.identifier
				&& (record.version == nil || record.version == subject.version)
				&& matchesPackage(record, subject: subject)
		}
		if let denied = matching.first(where: { $0.directive == .deny }) {
			return .deny(denied)
		}
		if let allowed = matching.first(where: { $0.directive == .allow && $0.version == subject.version }) {
			return .allow(allowed)
		}
		return .missing
	}

	private static func parseRecord(_ line: String, source: URL?, line lineNumber: Int) throws -> VouchRecord {
		var parts = line.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
		guard let directiveText = parts.first else {
			throw VouchParseError.unknownDirective(line: lineNumber, value: line)
		}
		parts.removeFirst()
		let directive: VouchDirective
		switch directiveText {
		case "allow":
			directive = .allow
		case "deny":
			directive = .deny
		default:
			throw VouchParseError.unknownDirective(line: lineNumber, value: directiveText)
		}

		var fields: [String: String] = [:]
		var index = 0
		while index < parts.count {
			let part = parts[index]
			guard let separator = part.firstIndex(of: ":") else {
				throw VouchParseError.malformedField(line: lineNumber, value: part)
			}
			let key = String(part[..<separator])
			let valueStart = part.index(after: separator)
			let value = String(part[valueStart...])
			if key == "reason" {
				fields[key] = ([value] + parts.dropFirst(index + 1)).joined(separator: " ")
				break
			}
			guard !key.isEmpty, !value.isEmpty else {
				throw VouchParseError.malformedField(line: lineNumber, value: part)
			}
			guard fields[key] == nil else {
				throw VouchParseError.duplicateField(line: lineNumber, field: key)
			}
			fields[key] = value
			index += 1
		}

		let sha256 = try requireField("sha256", in: fields, line: lineNumber)
		guard isSHA256(sha256) else {
			throw VouchParseError.invalidSHA256(line: lineNumber, value: sha256)
		}
		let identifier = try requireField("id", in: fields, line: lineNumber)
		let version = fields["version"]
		let packageKind: VouchPackageKind?
		if let value = fields["kind"] {
			guard let kind = VouchPackageKind(rawValue: value) else {
				throw VouchParseError.invalidPackageKind(line: lineNumber, value: value)
			}
			packageKind = kind
		} else {
			packageKind = nil
		}
		let packageScope: VouchPackageScope?
		if let value = fields["scope"] {
			guard let scope = VouchPackageScope(rawValue: value) else {
				throw VouchParseError.invalidPackageScope(line: lineNumber, value: value)
			}
			packageScope = scope
		} else {
			packageScope = nil
		}
		if packageScope != nil, packageKind != .luaPlugin {
			throw VouchParseError.scopeRequiresLuaPlugin(line: lineNumber)
		}
		if packageKind == .luaPlugin, packageScope == nil {
			throw VouchParseError.luaPluginScopeRequired(line: lineNumber)
		}
		let capabilities = try parseCapabilities(fields["capabilities"], line: lineNumber)
		if !capabilities.isEmpty, packageKind != .luaPlugin {
			throw VouchParseError.capabilitiesRequireLuaPlugin(line: lineNumber)
		}
		if !capabilities.isEmpty, directive != .allow {
			throw VouchParseError.capabilitiesRequireAllow(line: lineNumber)
		}
		if directive == .allow {
			_ = try requireField("version", in: fields, line: lineNumber)
			_ = try requireField("signer", in: fields, line: lineNumber)
		}
		return VouchRecord(
			directive: directive,
			sha256: sha256,
			identifier: identifier,
			version: version,
			packageKind: packageKind,
			packageScope: packageScope,
			capabilities: capabilities,
			signer: fields["signer"],
			reason: fields["reason"],
			source: source,
			line: lineNumber
		)
	}

	private static func requireField(_ field: String, in fields: [String: String], line: Int) throws -> String {
		guard let value = fields[field], !value.isEmpty else {
			throw VouchParseError.missingField(line: line, field: field)
		}
		return value
	}

	private static func parseCapabilities(_ value: String?, line: Int) throws -> Set<LuaPluginCapability> {
		guard let value else { return [] }
		var capabilities: Set<LuaPluginCapability> = []
		for rawValue in value.split(separator: ",", omittingEmptySubsequences: false) {
			let value = String(rawValue)
			guard let capability = LuaPluginCapability(rawValue: value), LuaPluginCapability.vouchGrantable.contains(capability) else {
				throw VouchParseError.invalidCapability(line: line, value: value)
			}
			guard capabilities.insert(capability).inserted else {
				throw VouchParseError.duplicateCapability(line: line, value: value)
			}
		}
		return capabilities
	}

	private static func isSHA256(_ value: String) -> Bool {
		guard value.count == 64 else {
			return false
		}
		let hex = Set("0123456789abcdefABCDEF")
		return value.allSatisfy { hex.contains($0) }
	}

	private func matchesPackage(_ record: VouchRecord, subject: VouchSubject) -> Bool {
		if record.directive == .deny {
			return (record.packageKind == nil || record.packageKind == subject.packageKind)
				&& (record.packageScope == nil || record.packageScope == subject.packageScope)
		}
		if subject.packageKind == .luaPlugin {
			return record.packageKind == .luaPlugin && record.packageScope == subject.packageScope
		}
		return (record.packageKind == nil || record.packageKind == subject.packageKind) && record.packageScope == nil
	}
}
