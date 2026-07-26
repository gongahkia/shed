import Foundation

public enum HotKeyExternalOwner: String, Equatable, Sendable {
    case macOSSymbolicHotKey = "macOS symbolic hotkey"
    case karabinerElements = "Karabiner-Elements"
    case skhd = "skhd"
}

public struct HotKeyChord: Equatable, Hashable, Sendable, CustomStringConvertible {
    public let keyCode: UInt32
    public let modifiers: UInt32

    public init(keyCode: UInt32, modifiers: UInt32) {
        self.keyCode = keyCode
        self.modifiers = modifiers
    }

    init(_ chord: KeyChord) {
        self.init(keyCode: chord.key.rawValue, modifiers: chord.modifiers.carbonFlags)
    }

    public var description: String {
        HotKeyNameMapper.describe(chord: self)
    }
}

public struct ExternalHotKey: Equatable, Sendable {
    public let owner: HotKeyExternalOwner
    public let chord: HotKeyChord
    public let detail: String

    public init(owner: HotKeyExternalOwner, chord: HotKeyChord, detail: String) {
        self.owner = owner
        self.chord = chord
        self.detail = detail
    }
}

public struct HotKeySourceError: Equatable, Sendable {
    public let owner: HotKeyExternalOwner
    public let detail: String

    public init(owner: HotKeyExternalOwner, detail: String) {
        self.owner = owner
        self.detail = detail
    }
}

public struct HotKeyScanResult: Equatable, Sendable {
    public let hotKeys: [ExternalHotKey]
    public let sourceErrors: [HotKeySourceError]

    public init(hotKeys: [ExternalHotKey], sourceErrors: [HotKeySourceError] = []) {
        self.hotKeys = hotKeys
        self.sourceErrors = sourceErrors
    }
}

public struct HotKeyCollision: Equatable, Sendable, CustomStringConvertible {
    public let chord: HotKeyChord
    public let action: Action
    public let externalOwner: HotKeyExternalOwner
    public let externalDetail: String

    public init(
        chord: HotKeyChord,
        action: Action,
        externalOwner: HotKeyExternalOwner,
        externalDetail: String
    ) {
        self.chord = chord
        self.action = action
        self.externalOwner = externalOwner
        self.externalDetail = externalDetail
    }

    public var description: String {
        "\(chord) overlaps \(externalOwner.rawValue) (\(externalDetail)); olly action \(String(describing: action))"
    }
}

public struct HotKeyCollisionReport: Equatable, Sendable {
    public let collisions: [HotKeyCollision]
    public let sourceErrors: [HotKeySourceError]

    public init(collisions: [HotKeyCollision], sourceErrors: [HotKeySourceError] = []) {
        self.collisions = collisions
        self.sourceErrors = sourceErrors
    }
}

public struct HotKeyCollisionDetector {
    private let externalHotKeys: () -> HotKeyScanResult

    public init(externalHotKeys: @escaping () -> HotKeyScanResult) {
        self.externalHotKeys = externalHotKeys
    }

    public static func live(
        fileManager: FileManager = .default,
        homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser
    ) -> HotKeyCollisionDetector {
        HotKeyCollisionDetector {
            LiveHotKeySources.scan(fileManager: fileManager, homeDirectory: homeDirectory)
        }
    }

    public func report(for keybinds: Keybinds) -> HotKeyCollisionReport {
        let scan = externalHotKeys()
        let externalByChord = Dictionary(grouping: scan.hotKeys, by: \.chord)
        let collisions = keybinds.bindings.flatMap { binding -> [HotKeyCollision] in
            let chord = HotKeyChord(binding.chord)
            return externalByChord[chord, default: []].map { external in
                HotKeyCollision(
                    chord: chord,
                    action: binding.action,
                    externalOwner: external.owner,
                    externalDetail: external.detail
                )
            }
        }
        return HotKeyCollisionReport(collisions: collisions, sourceErrors: scan.sourceErrors)
    }
}

enum LiveHotKeySources {
    static func scan(fileManager: FileManager, homeDirectory: URL) -> HotKeyScanResult {
        var hotKeys: [ExternalHotKey] = []
        var sourceErrors: [HotKeySourceError] = []

        do {
            hotKeys += try SymbolicHotKeyReader.read()
        } catch {
            sourceErrors.append(HotKeySourceError(
                owner: .macOSSymbolicHotKey,
                detail: String(describing: error)
            ))
        }

        appendKarabinerHotKeys(
            to: &hotKeys,
            sourceErrors: &sourceErrors,
            fileManager: fileManager,
            homeDirectory: homeDirectory
        )
        appendSkhdHotKeys(
            to: &hotKeys,
            sourceErrors: &sourceErrors,
            fileManager: fileManager,
            homeDirectory: homeDirectory
        )

        return HotKeyScanResult(hotKeys: hotKeys, sourceErrors: sourceErrors)
    }

    private static func appendKarabinerHotKeys(
        to hotKeys: inout [ExternalHotKey],
        sourceErrors: inout [HotKeySourceError],
        fileManager: FileManager,
        homeDirectory: URL
    ) {
        let url = homeDirectory
            .appendingPathComponent(".config", isDirectory: true)
            .appendingPathComponent("karabiner", isDirectory: true)
            .appendingPathComponent("karabiner.json")
        guard fileManager.fileExists(atPath: url.path) else {
            return
        }
        do {
            hotKeys += try KarabinerHotKeyParser.parse(data: Data(contentsOf: url), sourceURL: url)
        } catch {
            sourceErrors.append(HotKeySourceError(
                owner: .karabinerElements,
                detail: "\(url.path): \(String(describing: error))"
            ))
        }
    }

    private static func appendSkhdHotKeys(
        to hotKeys: inout [ExternalHotKey],
        sourceErrors: inout [HotKeySourceError],
        fileManager: FileManager,
        homeDirectory: URL
    ) {
        for url in skhdConfigURLs(homeDirectory: homeDirectory) where fileManager.fileExists(atPath: url.path) {
            do {
                hotKeys += SkhdHotKeyParser.parse(source: try String(contentsOf: url), sourceURL: url)
            } catch {
                sourceErrors.append(HotKeySourceError(
                    owner: .skhd,
                    detail: "\(url.path): \(String(describing: error))"
                ))
            }
        }
    }

    private static func skhdConfigURLs(homeDirectory: URL) -> [URL] {
        let xdgHome = ProcessInfo.processInfo.environment["XDG_CONFIG_HOME"].map(URL.init(fileURLWithPath:))
        return [
            xdgHome?.appendingPathComponent("skhd", isDirectory: true).appendingPathComponent("skhdrc"),
            homeDirectory
                .appendingPathComponent(".config", isDirectory: true)
                .appendingPathComponent("skhd", isDirectory: true)
                .appendingPathComponent("skhdrc"),
            homeDirectory.appendingPathComponent(".skhdrc")
        ].compactMap { $0 }
    }
}
