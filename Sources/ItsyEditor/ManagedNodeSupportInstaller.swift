import CryptoKit
import Foundation

public enum ManagedNodeSupportInstallError: Error, Equatable, Sendable {
	case invalidDescriptor(String)
	case invalidIntegrity(String)
	case invalidArchiveURL(String)
	case downloadStatus(String, Int)
	case integrityMismatch(String)
	case missingArchive(String)
	case archiveListingFailed(String)
	case unsafeArchivePath(String)
	case archiveExtractionFailed(String)
	case symlinkRejected(String)
	case missingPackage(String)
	case missingExecutable(String)
	case installedVersionExists(String)
}

public enum ManagedNodeSupportInstaller {
	public static func downloadAndInstall(
		component: ManagedSupportComponent,
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		fileManager: FileManager = .default,
		session: URLSession = .shared
	) async throws -> ManagedSupportInstallReceipt {
		let support = try validatedSupport(for: component)
		var archives: [String: URL] = [:]
		for package in support.packages {
			guard package.archiveURL.scheme?.lowercased() == "https" else {
				throw ManagedNodeSupportInstallError.invalidArchiveURL(package.archiveURL.absoluteString)
			}
			let (archiveURL, response) = try await session.download(from: package.archiveURL)
			guard let response = response as? HTTPURLResponse, (200 ... 299).contains(response.statusCode) else {
				throw ManagedNodeSupportInstallError.downloadStatus(
					package.packageName,
					(response as? HTTPURLResponse)?.statusCode ?? -1
				)
			}
			guard hasExpectedIntegrity(archiveURL, expected: package.integrity) else {
				throw ManagedNodeSupportInstallError.integrityMismatch(package.packageName)
			}
			archives[package.packageName] = archiveURL
		}
		defer {
			for archive in archives.values {
				try? fileManager.removeItem(at: archive)
			}
		}
		return try install(component: component, archives: archives, installRoot: installRoot, fileManager: fileManager)
	}

	public static func install(
		component: ManagedSupportComponent,
		archives: [String: URL],
		installRoot: URL = ManagedSupportInstaller.defaultInstallRoot(),
		fileManager: FileManager = .default
	) throws -> ManagedSupportInstallReceipt {
		let support = try validatedSupport(for: component)
		let destination = installRoot
			.appendingPathComponent(component.id, isDirectory: true)
			.appendingPathComponent(support.version, isDirectory: true)
		guard !fileManager.fileExists(atPath: destination.path) else {
			throw ManagedNodeSupportInstallError.installedVersionExists(destination.path)
		}
		let parent = destination.deletingLastPathComponent()
		try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
		let temporary = parent.appendingPathComponent(".\(support.version).tmp-\(UUID().uuidString)", isDirectory: true)
		do {
			let nodeModules = temporary.appendingPathComponent("node_modules", isDirectory: true)
			try fileManager.createDirectory(at: nodeModules, withIntermediateDirectories: true)
			for package in support.packages {
				guard let archive = archives[package.packageName] else {
					throw ManagedNodeSupportInstallError.missingArchive(package.packageName)
				}
				guard hasExpectedIntegrity(archive, expected: package.integrity) else {
					throw ManagedNodeSupportInstallError.integrityMismatch(package.packageName)
				}
				let extractedPackage = try extractPackage(
					archive,
					packageName: package.packageName,
					into: temporary,
					fileManager: fileManager
				)
				let target = nodeModules.appendingPathComponent(package.packageName, isDirectory: true)
				try fileManager.moveItem(at: extractedPackage, to: target)
				try validatePackageTree(target, fileManager: fileManager)
			}
			let executable = temporary.appendingPathComponent(support.executablePath).standardizedFileURL
			guard fileManager.fileExists(atPath: executable.path) else {
				throw ManagedNodeSupportInstallError.missingExecutable(support.executablePath)
			}
			try fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: executable.path)
			let receipt = try ManagedSupportInstallReceipt(
				componentID: component.id,
				version: support.version,
				sha256: ManagedSupportInstaller.sha256(url: temporary),
				executablePaths: [support.executablePath]
			)
			try JSONEncoder().encode(receipt).write(
				to: ManagedSupportInstaller.receiptURL(installedURL: temporary),
				options: .atomic
			)
			try fileManager.moveItem(at: temporary, to: destination)
			return receipt
		} catch {
			try? fileManager.removeItem(at: temporary)
			throw error
		}
	}

	private static func validatedSupport(for component: ManagedSupportComponent) throws -> ManagedNodeSupport {
		guard component.installMode == .managed, let support = component.nodeSupport else {
			throw ManagedNodeSupportInstallError.invalidDescriptor(component.id)
		}
		guard isSafePathComponent(component.id), isSafePathComponent(support.version), !support.packages.isEmpty,
		      isSafeRelativePath(support.executablePath)
		else {
			throw ManagedNodeSupportInstallError.invalidDescriptor(component.id)
		}
		for package in support.packages {
			guard isSafePathComponent(package.packageName), isSafePathComponent(package.version) else {
				throw ManagedNodeSupportInstallError.invalidDescriptor(package.packageName)
			}
			guard validIntegrity(package.integrity) else {
				throw ManagedNodeSupportInstallError.invalidIntegrity(package.packageName)
			}
		}
		let names = support.packages.map(\.packageName)
		guard Set(names).count == names.count else {
			throw ManagedNodeSupportInstallError.invalidDescriptor(component.id)
		}
		return support
	}

	private static func extractPackage(_ archive: URL, packageName: String, into root: URL,
	                                   fileManager: FileManager) throws -> URL
	{
		let entries = try archiveEntries(archive)
		for entry in entries where !isSafeArchiveEntry(entry) {
			throw ManagedNodeSupportInstallError.unsafeArchivePath(entry)
		}
		let stage = root.appendingPathComponent(".npm-stage-\(UUID().uuidString)", isDirectory: true)
		try fileManager.createDirectory(at: stage, withIntermediateDirectories: true)
		do {
			let process = Process()
			process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
			process.arguments = ["-xzf", archive.path, "-C", stage.path]
			process.standardOutput = Pipe()
			process.standardError = Pipe()
			try process.run()
			process.waitUntilExit()
			guard process.terminationStatus == 0 else {
				throw ManagedNodeSupportInstallError.archiveExtractionFailed(packageName)
			}
			let source = stage.appendingPathComponent("package", isDirectory: true)
			guard fileManager.fileExists(atPath: source.path) else {
				throw ManagedNodeSupportInstallError.missingPackage(packageName)
			}
			let target = root.appendingPathComponent(".package-\(UUID().uuidString)", isDirectory: true)
			try fileManager.moveItem(at: source, to: target)
			try fileManager.removeItem(at: stage)
			return target
		} catch {
			try? fileManager.removeItem(at: stage)
			throw error
		}
	}

	private static func archiveEntries(_ archive: URL) throws -> [String] {
		let process = Process()
		process.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
		process.arguments = ["-tzf", archive.path]
		let output = Pipe()
		process.standardOutput = output
		process.standardError = Pipe()
		do {
			try process.run()
			process.waitUntilExit()
		} catch {
			throw ManagedNodeSupportInstallError.archiveListingFailed(archive.lastPathComponent)
		}
		guard process.terminationStatus == 0 else {
			throw ManagedNodeSupportInstallError.archiveListingFailed(archive.lastPathComponent)
		}
		return String(decoding: output.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
			.split(separator: "\n")
			.map(String.init)
	}

	private static func validatePackageTree(_ root: URL, fileManager: FileManager) throws {
		let keys: Set<URLResourceKey> = [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
		guard let enumerator = fileManager.enumerator(at: root, includingPropertiesForKeys: Array(keys), options: []) else {
			throw ManagedNodeSupportInstallError.missingPackage(root.lastPathComponent)
		}
		for case let url as URL in enumerator {
			let values = try url.resourceValues(forKeys: keys)
			if values.isSymbolicLink == true {
				throw ManagedNodeSupportInstallError.symlinkRejected(url.path)
			}
			if values.isDirectory == true, url.pathExtension == "app" {
				throw ManagedNodeSupportInstallError.invalidDescriptor(url.path)
			}
			if values.isRegularFile == true {
				try fileManager.setAttributes([.posixPermissions: 0o644], ofItemAtPath: url.path)
			}
		}
		guard fileManager.fileExists(atPath: root.appendingPathComponent("package.json").path) else {
			throw ManagedNodeSupportInstallError.missingPackage(root.lastPathComponent)
		}
	}

	private static func hasExpectedIntegrity(_ archive: URL, expected: String) -> Bool {
		guard validIntegrity(expected), let digest = try? Data(contentsOf: archive) else {
			return false
		}
		return expected == "sha512-\(Data(SHA512.hash(data: digest)).base64EncodedString())"
	}

	private static func validIntegrity(_ value: String) -> Bool {
		guard value.hasPrefix("sha512-") else {
			return false
		}
		return Data(base64Encoded: String(value.dropFirst("sha512-".count))) != nil
	}

	private static func isSafeArchiveEntry(_ value: String) -> Bool {
		value == "package" || (value.hasPrefix("package/") && isSafeRelativePath(String(value.dropFirst("package/".count))))
	}

	private static func isSafePathComponent(_ value: String) -> Bool {
		!value.isEmpty && value.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == "." }
	}

	private static func isSafeRelativePath(_ value: String) -> Bool {
		!value.hasPrefix("/") && !value.split(separator: "/").contains(where: { $0.isEmpty || $0 == "." || $0 == ".." })
	}
}
