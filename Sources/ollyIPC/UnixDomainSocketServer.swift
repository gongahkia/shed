import Darwin
import Dispatch
import Foundation

public final class UnixDomainSocketServerConnection: @unchecked Sendable {
    public let id: UUID

    private let descriptor: Int32
    private let queue: DispatchQueue
    private let onClose: @Sendable (Int32) -> Void
    private var isClosed = false
    private var closeHandlers: [@Sendable () -> Void] = []

    init(descriptor: Int32, queue: DispatchQueue, onClose: @escaping @Sendable (Int32) -> Void) {
        self.id = UUID()
        self.descriptor = descriptor
        self.queue = queue
        self.onClose = onClose
    }

    public func sendLine(_ line: Data) {
        queue.async { [self] in
            guard !isClosed else {
                return
            }
            try? IPCPOSIX.writeAll(JSONLineCodec.appendLineDelimiter(to: line), to: descriptor)
        }
    }

    public func onClose(_ handler: @escaping @Sendable () -> Void) {
        queue.async { [self] in
            if isClosed {
                handler()
            } else {
                closeHandlers.append(handler)
            }
        }
    }

    public func close() {
        queue.async { [descriptor, onClose] in
            onClose(descriptor)
        }
    }

    func markClosedOnQueue() {
        guard !isClosed else {
            return
        }
        isClosed = true
        let handlers = closeHandlers
        closeHandlers.removeAll()
        handlers.forEach { $0() }
    }
}

public final class UnixDomainSocketServer {
    public typealias LineHandler = @Sendable (Data) throws -> Data?
    public typealias ConnectionLineHandler = @Sendable (UnixDomainSocketServerConnection, Data) async -> Void

    public let socketPath: IPCSocketPath
    public let maxLineBytes: Int

    private let handler: LineHandler?
    private let connectionHandler: ConnectionLineHandler?
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Void>()
    private let fileManager: FileManager
    private var listenFD: Int32 = -1
    private var listenSource: DispatchSourceRead?
    private var clientSources: [Int32: DispatchSourceRead] = [:]
    private var clientBuffers: [Int32: Data] = [:]
    private var clientConnections: [Int32: UnixDomainSocketServerConnection] = [:]

    public init(
        socketPath: IPCSocketPath = .resolved(),
        queueLabel: String = "dev.olly.ipc.socket-server",
        maxLineBytes: Int = 1_048_576,
        fileManager: FileManager = .default,
        handler: @escaping LineHandler
    ) {
        self.socketPath = socketPath
        self.maxLineBytes = maxLineBytes
        self.fileManager = fileManager
        self.handler = handler
        self.connectionHandler = nil
        self.queue = DispatchQueue(label: queueLabel)
        self.queue.setSpecific(key: queueKey, value: ())
    }

    public init(
        socketPath: IPCSocketPath = .resolved(),
        queueLabel: String = "dev.olly.ipc.socket-server",
        maxLineBytes: Int = 1_048_576,
        fileManager: FileManager = .default,
        connectionHandler: @escaping ConnectionLineHandler
    ) {
        self.socketPath = socketPath
        self.maxLineBytes = maxLineBytes
        self.fileManager = fileManager
        self.handler = nil
        self.connectionHandler = connectionHandler
        self.queue = DispatchQueue(label: queueLabel)
        self.queue.setSpecific(key: queueKey, value: ())
    }

    deinit {
        stop()
    }

    public var isRunning: Bool {
        queue.sync {
            listenFD >= 0
        }
    }

    public func start() throws {
        try syncOnQueue {
            try startOnQueue()
        }
    }

    public func stop() {
        syncOnQueue {
            stopOnQueue()
        }
    }

    private func startOnQueue() throws {
        guard listenFD < 0 else {
            throw IPCSocketError.alreadyRunning(socketPath.rawValue)
        }

        try prepareSocketPath()

        let descriptor = try IPCPOSIX.openUnixStreamSocket()
        do {
            try IPCPOSIX.setCloseOnExec(descriptor)
            try IPCPOSIX.setNonBlocking(descriptor)
            try IPCUnixSocketAddress.withSockAddr(path: socketPath.rawValue) { address, length in
                guard Darwin.bind(descriptor, address, length) == 0 else {
                    throw IPCSocketError.current(function: "bind")
                }
            }
            guard Darwin.listen(descriptor, SOMAXCONN) == 0 else {
                throw IPCSocketError.current(function: "listen")
            }
        } catch {
            IPCPOSIX.close(descriptor)
            throw error
        }

        listenFD = descriptor
        let source = DispatchSource.makeReadSource(fileDescriptor: descriptor, queue: queue)
        source.setEventHandler { [weak self] in
            self?.acceptAvailableClients()
        }
        listenSource = source
        source.resume()
    }

    private func stopOnQueue() {
        listenSource?.cancel()
        listenSource = nil

        if listenFD >= 0 {
            IPCPOSIX.close(listenFD)
            listenFD = -1
        }

        for descriptor in Array(clientSources.keys) {
            closeClient(descriptor)
        }

        if fileManager.fileExists(atPath: socketPath.rawValue) {
            try? fileManager.removeItem(atPath: socketPath.rawValue)
        }
    }

    private func prepareSocketPath() throws {
        try fileManager.createDirectory(
            at: socketPath.directoryURL,
            withIntermediateDirectories: true
        )

        guard fileManager.fileExists(atPath: socketPath.rawValue) else {
            return
        }

        guard try isSocketFile(atPath: socketPath.rawValue) else {
            throw IPCSocketError.socketPathOccupied(socketPath.rawValue)
        }

        guard !UnixDomainSocketClient.canConnect(to: socketPath.rawValue) else {
            throw IPCSocketError.socketPathInUse(socketPath.rawValue)
        }

        try fileManager.removeItem(atPath: socketPath.rawValue)
    }

    private func isSocketFile(atPath path: String) throws -> Bool {
        var info = stat()
        guard Darwin.lstat(path, &info) == 0 else {
            throw IPCSocketError.current(function: "lstat")
        }
        return (info.st_mode & S_IFMT) == S_IFSOCK
    }

    private func acceptAvailableClients() {
        while listenFD >= 0 {
            let clientFD = Darwin.accept(listenFD, nil, nil)
            if clientFD >= 0 {
                registerClient(clientFD)
                continue
            }

            if errno == EINTR {
                continue
            }
            if errno == EAGAIN || errno == EWOULDBLOCK {
                return
            }
            return
        }
    }

    private func registerClient(_ descriptor: Int32) {
        do {
            try IPCPOSIX.setCloseOnExec(descriptor)
            try IPCPOSIX.setNonBlocking(descriptor)
        } catch {
            IPCPOSIX.close(descriptor)
            return
        }

        let source = DispatchSource.makeReadSource(fileDescriptor: descriptor, queue: queue)
        source.setEventHandler { [weak self] in
            self?.readAvailableData(from: descriptor)
        }
        let connection = UnixDomainSocketServerConnection(
            descriptor: descriptor,
            queue: queue
        ) { [weak self] descriptor in
            self?.closeClient(descriptor)
        }
        clientSources[descriptor] = source
        clientBuffers[descriptor] = Data()
        clientConnections[descriptor] = connection
        source.resume()
    }

    private func readAvailableData(from descriptor: Int32) {
        var chunk = [UInt8](repeating: 0, count: 4_096)

        while true {
            let count = chunk.withUnsafeMutableBytes { rawBuffer in
                Darwin.read(descriptor, rawBuffer.baseAddress, rawBuffer.count)
            }

            if count > 0 {
                clientBuffers[descriptor, default: Data()].append(contentsOf: chunk.prefix(count))
                if clientBuffers[descriptor, default: Data()].count > maxLineBytes {
                    closeClient(descriptor)
                    return
                }
                processBufferedLines(for: descriptor)
                continue
            }

            if count == 0 {
                closeClient(descriptor)
                return
            }

            if errno == EINTR {
                continue
            }
            if errno == EAGAIN || errno == EWOULDBLOCK {
                return
            }
            closeClient(descriptor)
            return
        }
    }

    private func processBufferedLines(for descriptor: Int32) {
        while var buffer = clientBuffers[descriptor], let line = JSONLineCodec.popLine(from: &buffer) {
            clientBuffers[descriptor] = buffer
            do {
                if let handler, let response = try handler(line) {
                    try IPCPOSIX.writeAll(JSONLineCodec.appendLineDelimiter(to: response), to: descriptor)
                }
                if let connectionHandler, let connection = clientConnections[descriptor] {
                    Task {
                        await connectionHandler(connection, line)
                    }
                }
            } catch {
                closeClient(descriptor)
                return
            }
        }
    }

    private func closeClient(_ descriptor: Int32) {
        clientSources[descriptor]?.cancel()
        clientSources[descriptor] = nil
        clientBuffers[descriptor] = nil
        clientConnections[descriptor]?.markClosedOnQueue()
        clientConnections[descriptor] = nil
        IPCPOSIX.close(descriptor)
    }

    private func syncOnQueue<T>(_ body: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return try body()
        }
        return try queue.sync(execute: body)
    }
}
