import ArgumentParser
import Foundation
import ollyDSL

enum InitConfigError: Error, CustomStringConvertible, Equatable {
    case alreadyExists(String)

    var description: String {
        switch self {
        case let .alreadyExists(path):
            return "Config.swift already exists: \(path)"
        }
    }
}

struct InitConfigResult: Equatable {
    let profile: ConfigTemplateProfile
    let url: URL
    let didOverwrite: Bool
}

struct ConfigInitializer {
    let fileManager: FileManager

    init(fileManager: FileManager = .default) {
        self.fileManager = fileManager
    }

    func write(profile: ConfigTemplateProfile, to url: URL, force: Bool) throws -> InitConfigResult {
        try fileManager.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let exists = fileManager.fileExists(atPath: url.path)
        if exists && !force {
            throw InitConfigError.alreadyExists(url.path)
        }
        try profile.source.write(to: url, atomically: true, encoding: .utf8)
        return InitConfigResult(profile: profile, url: url, didOverwrite: exists)
    }
}

struct InitConfig: ParsableCommand {
    static let configuration = CommandConfiguration(
        commandName: "init-config",
        abstract: "Create a starter Config.swift profile."
    )

    @Option(name: .customLong("config"), help: "Path to Config.swift.")
    var configPath: String?

    @Option(help: "Profile: starter, minimal, niri, bsp, ultrawide.")
    var profile = ConfigTemplateProfile.defaultProfile.rawValue

    @Flag(help: "Overwrite an existing Config.swift.")
    var force = false

    @Flag(help: "List available profiles.")
    var listProfiles = false

    func run() throws {
        if listProfiles {
            print(Self.profileList())
            return
        }
        let selectedProfile = try ConfigTemplateProfile(name: profile)
        let url = configPath.map(URL.init(fileURLWithPath:)) ?? ConfigLoader.defaultSourceURL()
        let result = try ConfigInitializer().write(profile: selectedProfile, to: url, force: force)
        let action = result.didOverwrite ? "overwrote" : "created"
        print("\(action) \(result.url.path) with \(result.profile.rawValue) profile")
    }

    static func profileList() -> String {
        ConfigTemplateProfile.allCases.map { profile in
            "\(profile.rawValue): \(profile.summary)"
        }.joined(separator: "\n")
    }
}
