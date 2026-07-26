import CoreServices
import Foundation
import ollyKit

public struct ConfigReloadFailure: Equatable, Sendable {
    public let message: String
    public let retainedConfig: LoadedConfig?
}

public enum ConfigReloadEvent: Equatable, Sendable {
    case reloaded(LoadedConfig)
    case failed(ConfigReloadFailure)
}

public final class ConfigReloader {
    public typealias Loader = () throws -> LoadedConfig
    public typealias Notifier = @Sendable (ConfigReloadEvent) -> Void

    public let sourceURL: URL

    private let fileManager: FileManager
    private let load: Loader
    private let notify: Notifier
    private let queue = DispatchQueue(label: "olly.dsl.config-reloader")
    private var stream: FSEventStreamRef?
    private var loadedConfig: LoadedConfig?

    public convenience init(loader: ConfigLoader, notify: @escaping Notifier = { _ in }) {
        self.init(sourceURL: loader.sourceURL, load: loader.load, notify: notify)
    }

    public init(
        sourceURL: URL = ConfigLoader.defaultSourceURL(),
        fileManager: FileManager = .default,
        load: @escaping Loader,
        notify: @escaping Notifier = { _ in }
    ) {
        self.sourceURL = sourceURL
        self.fileManager = fileManager
        self.load = load
        self.notify = notify
    }

    deinit {
        stop()
    }

    public var currentConfig: LoadedConfig? {
        queue.sync {
            loadedConfig
        }
    }

    public func start() throws {
        try fileManager.createDirectory(at: sourceURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try queue.sync {
            guard stream == nil else {
                return
            }
            var context = FSEventStreamContext(
                version: 0,
                info: Unmanaged.passUnretained(self).toOpaque(),
                retain: nil,
                release: nil,
                copyDescription: nil
            )
            let paths = [sourceURL.deletingLastPathComponent().path] as CFArray
            guard let stream = FSEventStreamCreate(
                nil,
                configReloaderFSEventCallback,
                &context,
                paths,
                FSEventStreamEventId(kFSEventStreamEventIdSinceNow),
                0.25,
                UInt32(kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagNoDefer)
            ) else {
                throw ConfigReloaderError.watchCreationFailed(sourceURL.deletingLastPathComponent())
            }
            FSEventStreamSetDispatchQueue(stream, queue)
            guard FSEventStreamStart(stream) else {
                FSEventStreamInvalidate(stream)
                FSEventStreamRelease(stream)
                throw ConfigReloaderError.watchStartFailed(sourceURL.deletingLastPathComponent())
            }
            self.stream = stream
            _ = reloadOnQueue()
        }
    }

    public func stop() {
        queue.sync {
            guard let stream else {
                return
            }
            FSEventStreamStop(stream)
            FSEventStreamInvalidate(stream)
            FSEventStreamRelease(stream)
            self.stream = nil
        }
    }

    @discardableResult
    public func reloadNow() -> ConfigReloadEvent {
        queue.sync {
            reloadOnQueue()
        }
    }

    fileprivate func reloadAsync() {
        queue.async { [weak self] in
            _ = self?.reloadOnQueue()
        }
    }

    private func reloadOnQueue() -> ConfigReloadEvent {
        PerformanceSignpost.interval("dsl.reload") {
            do {
                let loaded = try load()
                loadedConfig = loaded
                let event = ConfigReloadEvent.reloaded(loaded)
                notify(event)
                return event
            } catch {
                let event = ConfigReloadEvent.failed(
                    ConfigReloadFailure(message: String(describing: error), retainedConfig: loadedConfig)
                )
                notify(event)
                return event
            }
        }
    }
}

public enum ConfigReloaderError: Error, Equatable, Sendable {
    case watchCreationFailed(URL)
    case watchStartFailed(URL)
}

private let configReloaderFSEventCallback: FSEventStreamCallback = { _, info, _, _, _, _ in
    guard let info else {
        return
    }
    let reloader = Unmanaged<ConfigReloader>.fromOpaque(info).takeUnretainedValue()
    reloader.reloadAsync()
}
