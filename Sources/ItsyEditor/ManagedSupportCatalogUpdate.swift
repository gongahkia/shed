import CryptoKit
import Foundation

public enum ManagedSupportCatalogUpdateError: Error, Equatable, Sendable {
	case unavailable
	case downloadStatus(Int)
	case invalidEnvelope
	case invalidSignature
	case unsupportedSchema(Int)
	case incompatibleComponent(String)
}

public struct SignedManagedSupportCatalog: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var payload: Data
	public var signature: Data

	private enum CodingKeys: String, CodingKey {
		case schemaVersion = "schema_version"
		case payload
		case signature
	}

	public init(schemaVersion: Int = 1, payload: Data, signature: Data) {
		self.schemaVersion = schemaVersion
		self.payload = payload
		self.signature = signature
	}
}

public struct ManagedSupportCatalogUpdateConfiguration: Equatable, Sendable {
	public var url: URL
	public var publicKey: Data
	public var cacheDirectory: URL

	public init(url: URL, publicKey: Data, cacheDirectory: URL = Self.defaultCacheDirectory()) {
		self.url = url
		self.publicKey = publicKey
		self.cacheDirectory = cacheDirectory
	}

	public static func bundled() -> ManagedSupportCatalogUpdateConfiguration? {
		guard
			let urlString = Bundle.main.object(forInfoDictionaryKey: "ITSYLSPCatalogURL") as? String,
			let url = URL(string: urlString), url.scheme?.lowercased() == "https",
			let keyString = Bundle.main.object(forInfoDictionaryKey: "ITSYLSPCatalogPublicKey") as? String,
			let key = Data(base64Encoded: keyString), key.count == 32
		else {
			return nil
		}
		return .init(url: url, publicKey: key)
	}

	public static func defaultCacheDirectory(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
		homeDirectory
			.appendingPathComponent("Library", isDirectory: true)
			.appendingPathComponent("Application Support", isDirectory: true)
			.appendingPathComponent("Itsy", isDirectory: true)
			.appendingPathComponent("LSPCatalog", isDirectory: true)
	}
}

public enum ManagedSupportCatalogStore {
	private static let lock = NSLock()
	private static var overrideCatalog: ManagedSupportCatalog?

	public static func current() -> ManagedSupportCatalog {
		lock.lock()
		defer { lock.unlock() }
		return merged(overrideCatalog)
	}

	public static func install(_ catalog: ManagedSupportCatalog) throws {
		try validate(catalog)
		lock.lock()
		overrideCatalog = catalog
		lock.unlock()
	}

	private static func merged(_ catalog: ManagedSupportCatalog?) -> ManagedSupportCatalog {
		guard let catalog else { return .bundled }
		let updates = Dictionary(uniqueKeysWithValues: catalog.components.map { ($0.id, $0) })
		return ManagedSupportCatalog(components: ManagedSupportCatalog.bundled.components.map { updates[$0.id] ?? $0 })
	}

	public static func validate(_ catalog: ManagedSupportCatalog) throws {
		guard catalog.schemaVersion == 1 else { throw ManagedSupportCatalogUpdateError.unsupportedSchema(catalog.schemaVersion) }
		let bundled = Dictionary(uniqueKeysWithValues: ManagedSupportCatalog.bundled.components.map { ($0.id, $0) })
		for component in catalog.components {
			guard let existing = bundled[component.id],
			      component.kind == existing.kind,
			      component.languageIDs.sorted() == existing.languageIDs.sorted(),
			      component.command == existing.command,
			      component.arguments == existing.arguments,
			      component.installMode == existing.installMode
			else {
				throw ManagedSupportCatalogUpdateError.incompatibleComponent(component.id)
			}
		}
	}
}

public enum ManagedSupportCatalogUpdateClient {
	public static func loadActive(configuration: ManagedSupportCatalogUpdateConfiguration, fileManager: FileManager = .default) throws -> ManagedSupportCatalog? {
		let url = configuration.cacheDirectory.appendingPathComponent("active.json")
		guard fileManager.fileExists(atPath: url.path) else { return nil }
		let catalog = try verify(Data(contentsOf: url), publicKey: configuration.publicKey)
		try ManagedSupportCatalogStore.install(catalog)
		return catalog
	}

	public static func pending(configuration: ManagedSupportCatalogUpdateConfiguration, fileManager: FileManager = .default) throws -> ManagedSupportCatalog? {
		let url = configuration.cacheDirectory.appendingPathComponent("pending.json")
		guard fileManager.fileExists(atPath: url.path) else { return nil }
		return try verify(Data(contentsOf: url), publicKey: configuration.publicKey)
	}

	@discardableResult public static func check(
		configuration: ManagedSupportCatalogUpdateConfiguration,
		fileManager: FileManager = .default,
		session: URLSession = .shared
	) async throws -> ManagedSupportCatalog {
		let (data, response) = try await session.data(from: configuration.url)
		guard let response = response as? HTTPURLResponse else { throw ManagedSupportCatalogUpdateError.downloadStatus(-1) }
		guard (200 ... 299).contains(response.statusCode) else { throw ManagedSupportCatalogUpdateError.downloadStatus(response.statusCode) }
		let catalog = try verify(data, publicKey: configuration.publicKey)
		try fileManager.createDirectory(at: configuration.cacheDirectory, withIntermediateDirectories: true)
		try data.write(to: configuration.cacheDirectory.appendingPathComponent("pending.json"), options: .atomic)
		return catalog
	}

	@discardableResult public static func applyPending(
		configuration: ManagedSupportCatalogUpdateConfiguration,
		fileManager: FileManager = .default
	) throws -> ManagedSupportCatalog {
		let pendingURL = configuration.cacheDirectory.appendingPathComponent("pending.json")
		guard fileManager.fileExists(atPath: pendingURL.path) else { throw ManagedSupportCatalogUpdateError.unavailable }
		let data = try Data(contentsOf: pendingURL)
		let catalog = try verify(data, publicKey: configuration.publicKey)
		try ManagedSupportCatalogStore.install(catalog)
		try data.write(to: configuration.cacheDirectory.appendingPathComponent("active.json"), options: .atomic)
		return catalog
	}

	public static func verify(_ data: Data, publicKey: Data) throws -> ManagedSupportCatalog {
		guard publicKey.count == 32 else { throw ManagedSupportCatalogUpdateError.invalidEnvelope }
		let envelope: SignedManagedSupportCatalog
		do {
			envelope = try JSONDecoder().decode(SignedManagedSupportCatalog.self, from: data)
		} catch {
			throw ManagedSupportCatalogUpdateError.invalidEnvelope
		}
		guard envelope.schemaVersion == 1 else { throw ManagedSupportCatalogUpdateError.unsupportedSchema(envelope.schemaVersion) }
		do {
			let key = try Curve25519.Signing.PublicKey(rawRepresentation: publicKey)
			guard key.isValidSignature(envelope.signature, for: envelope.payload) else {
				throw ManagedSupportCatalogUpdateError.invalidSignature
			}
		} catch let error as ManagedSupportCatalogUpdateError {
			throw error
		} catch {
			throw ManagedSupportCatalogUpdateError.invalidSignature
		}
		do {
			let catalog = try JSONDecoder().decode(ManagedSupportCatalog.self, from: envelope.payload)
			try ManagedSupportCatalogStore.validate(catalog)
			return catalog
		} catch let error as ManagedSupportCatalogUpdateError {
			throw error
		} catch {
			throw ManagedSupportCatalogUpdateError.invalidEnvelope
		}
	}
}
