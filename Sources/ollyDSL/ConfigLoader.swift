import CryptoKit
import Darwin
import Foundation

public struct CachedConfigLibrary: Equatable, Sendable {
    public let contentHash: String
    public let libraryURL: URL
    public let didCompile: Bool
}

public struct LoadedConfig: Equatable, Sendable {
    public let config: Config
    public let libraryURL: URL
    public let contentHash: String
    public let didCompile: Bool
}

public enum ConfigLoaderError: Error, CustomStringConvertible {
    case missingSource(URL)
    case compileFailed(command: String, exitCode: Int32, output: String)
    case openFailed(URL, String)
    case missingSymbol(String, URL)
    case nilConfig(String)
    case invalidUTF8(URL)
    case dslVersionMismatch(DSLMigrationPrompt)

    public var description: String {
        switch self {
        case let .missingSource(url):
            return "missing config source: \(url.path)"
        case let .compileFailed(command, exitCode, output):
            return "config compile failed (\(exitCode)): \(command)\n\(output)"
        case let .openFailed(url, message):
            return "dlopen failed for \(url.path): \(message)"
        case let .missingSymbol(symbol, url):
            return "missing symbol \(symbol) in \(url.path)"
        case let .nilConfig(symbol):
            return "\(symbol) returned nil"
        case let .invalidUTF8(url):
            return "config library returned non-UTF8 JSON from \(url.path)"
        case let .dslVersionMismatch(prompt):
            if let diff = prompt.diffSuggestion, !diff.isEmpty {
                return "\(prompt.message)\nSuggested diff:\n\(diff)"
            }
            return prompt.message
        }
    }
}

public struct ConfigLoader {
    public let sourceURL: URL
    public let cacheDirectory: URL
    public let swiftcURL: URL
    public let moduleSearchPaths: [URL]
    public let librarySearchPaths: [URL]
    public let extraCompilerArguments: [String]

    private let fileManager: FileManager

    public init(
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        cacheDirectory: URL = ConfigLoader.defaultCacheDirectory(),
        swiftcURL: URL = URL(fileURLWithPath: "/usr/bin/swiftc"),
        moduleSearchPaths: [URL] = [],
        librarySearchPaths: [URL] = [],
        extraCompilerArguments: [String] = [],
        fileManager: FileManager = .default
    ) {
        self.sourceURL = sourceURL
        self.cacheDirectory = cacheDirectory
        self.swiftcURL = swiftcURL
        self.moduleSearchPaths = moduleSearchPaths
        self.librarySearchPaths = librarySearchPaths
        self.extraCompilerArguments = extraCompilerArguments
        self.fileManager = fileManager
    }

    public func load() throws -> LoadedConfig {
        let cached = try compileIfNeeded()
        let config = try loadConfig(from: cached.libraryURL)
        return LoadedConfig(
            config: config,
            libraryURL: cached.libraryURL,
            contentHash: cached.contentHash,
            didCompile: cached.didCompile
        )
    }

    public func compileIfNeeded() throws -> CachedConfigLibrary {
        guard fileManager.fileExists(atPath: sourceURL.path) else {
            throw ConfigLoaderError.missingSource(sourceURL)
        }

        let sourceData = try Data(contentsOf: sourceURL)
        let hash = Self.contentHash(for: sourceData)
        let cacheURL = cacheDirectory.appendingPathComponent(hash, isDirectory: true)
        let libraryURL = cacheURL.appendingPathComponent("Config.dylib")

        guard !fileManager.fileExists(atPath: libraryURL.path) else {
            return CachedConfigLibrary(contentHash: hash, libraryURL: libraryURL, didCompile: false)
        }

        try fileManager.createDirectory(at: cacheURL, withIntermediateDirectories: true)
        let shimURL = cacheURL.appendingPathComponent("OllyConfigShim.swift")
        try Self.shimSource().write(to: shimURL, atomically: true, encoding: .utf8)
        try runCompiler(sourceURL: sourceURL, shimURL: shimURL, libraryURL: libraryURL, logDirectory: cacheURL)
        return CachedConfigLibrary(contentHash: hash, libraryURL: libraryURL, didCompile: true)
    }

    public func loadConfig(from libraryURL: URL) throws -> Config {
        guard let handle = dlopen(libraryURL.path, RTLD_NOW | RTLD_LOCAL) else {
            throw ConfigLoaderError.openFailed(libraryURL, lastDynamicLoaderError())
        }
        defer { dlclose(handle) }

        guard let symbol = dlsym(handle, OllyDSL.exportedConfigSymbol) else {
            throw ConfigLoaderError.missingSymbol(OllyDSL.exportedConfigSymbol, libraryURL)
        }

        typealias JSONFunction = @convention(c) () -> UnsafeMutablePointer<CChar>?
        let makeJSON = unsafeBitCast(symbol, to: JSONFunction.self)
        guard let jsonPointer = makeJSON() else {
            throw ConfigLoaderError.nilConfig(OllyDSL.exportedConfigSymbol)
        }
        defer { free(jsonPointer) }

        guard let json = String(validatingUTF8: jsonPointer) else {
            throw ConfigLoaderError.invalidUTF8(libraryURL)
        }
        let config = try JSONDecoder().decode(Config.self, from: Data(json.utf8))
        if let prompt = DSLVersionMigrator.prompt(for: config.version) {
            throw ConfigLoaderError.dslVersionMismatch(prompt)
        }
        return config
    }

    public static func defaultSourceURL() -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("Config.swift")
    }

    public static func defaultCacheDirectory() -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".cache", isDirectory: true)
            .appendingPathComponent("olly", isDirectory: true)
            .appendingPathComponent("config", isDirectory: true)
    }

    private static func contentHash(for data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func shimSource() -> String {
        """
        import Foundation
        import ollyDSL

        @_cdecl("\(OllyDSL.exportedConfigSymbol)")
        public func \(OllyDSL.exportedConfigSymbol)() -> UnsafeMutablePointer<CChar>? {
            do {
                let data = try JSONEncoder().encode(\(OllyDSL.defaultConfigEntryPoint)())
                guard let json = String(data: data, encoding: .utf8) else {
                    return nil
                }
                return strdup(json)
            } catch {
                return nil
            }
        }
        """
    }

    private func runCompiler(sourceURL: URL, shimURL: URL, libraryURL: URL, logDirectory: URL) throws {
        let arguments = compilerArguments(sourceURL: sourceURL, shimURL: shimURL, libraryURL: libraryURL)
        let logURL = logDirectory.appendingPathComponent("swiftc.log")
        fileManager.createFile(atPath: logURL.path, contents: nil)
        let logHandle = try FileHandle(forWritingTo: logURL)
        defer { try? logHandle.close() }

        let process = Process()
        process.executableURL = swiftcURL
        process.arguments = arguments
        process.standardOutput = logHandle
        process.standardError = logHandle
        try process.run()
        process.waitUntilExit()

        guard process.terminationStatus == 0 else {
            let output = (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
            throw ConfigLoaderError.compileFailed(
                command: ([swiftcURL.path] + arguments).joined(separator: " "),
                exitCode: process.terminationStatus,
                output: output
            )
        }
    }

    private func compilerArguments(sourceURL: URL, shimURL: URL, libraryURL: URL) -> [String] {
        var arguments = [
            "-emit-library",
            "-parse-as-library",
            "-module-name",
            "OllyUserConfig",
            "-o",
            libraryURL.path
        ]
        for path in moduleSearchPaths {
            arguments += ["-I", path.path]
        }
        for path in librarySearchPaths {
            arguments += ["-L", path.path]
        }
        arguments += [
            "-Xlinker",
            "-undefined",
            "-Xlinker",
            "dynamic_lookup"
        ]
        arguments += extraCompilerArguments
        arguments += [sourceURL.path, shimURL.path]
        return arguments
    }

    private func lastDynamicLoaderError() -> String {
        guard let pointer = dlerror() else {
            return "unknown error"
        }
        return String(cString: pointer)
    }
}
