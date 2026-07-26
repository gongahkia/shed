import Darwin
import Foundation

public enum IPCSocketError: Error, Equatable, CustomStringConvertible, Sendable {
    case alreadyRunning(String)
    case notRunning(String)
    case pathTooLong(path: String, limit: Int)
    case socketPathInUse(String)
    case socketPathOccupied(String)
    case lineTooLong(limit: Int)
    case timedOut(String)
    case connectionClosedBeforeLine
    case systemCallFailed(function: String, errnoCode: Int32)

    public var description: String {
        switch self {
        case let .alreadyRunning(path):
            return "ipc socket server already running at \(path)"
        case let .notRunning(path):
            return "ipc socket server is not running at \(path)"
        case let .pathTooLong(path, limit):
            return "ipc socket path exceeds \(limit) bytes: \(path)"
        case let .socketPathInUse(path):
            return "ipc socket path is already in use: \(path)"
        case let .socketPathOccupied(path):
            return "ipc socket path is occupied by a non-socket file: \(path)"
        case let .lineTooLong(limit):
            return "ipc JSON line exceeds \(limit) bytes"
        case let .timedOut(path):
            return "timed out waiting for ipc response from \(path)"
        case .connectionClosedBeforeLine:
            return "ipc peer closed before sending a complete JSON line"
        case let .systemCallFailed(function, errnoCode):
            return "\(function) failed with errno \(errnoCode)"
        }
    }

    static func current(function: String) -> IPCSocketError {
        .systemCallFailed(function: function, errnoCode: errno)
    }
}

public struct UnixDomainSocketClient: Sendable {
    public let socketPath: IPCSocketPath
    public let timeout: TimeInterval
    public let maxLineBytes: Int

    public init(
        socketPath: IPCSocketPath = .resolved(),
        timeout: TimeInterval = 2,
        maxLineBytes: Int = 1_048_576
    ) {
        self.socketPath = socketPath
        self.timeout = timeout
        self.maxLineBytes = maxLineBytes
    }

    public func sendLine(_ line: Data) throws -> Data {
        let descriptor = try IPCPOSIX.openUnixStreamSocket()
        defer {
            IPCPOSIX.close(descriptor)
        }

        try IPCPOSIX.setSocketTimeout(timeout, on: descriptor)
        try IPCUnixSocketAddress.withSockAddr(path: socketPath.rawValue) { address, length in
            guard Darwin.connect(descriptor, address, length) == 0 else {
                throw IPCSocketError.current(function: "connect")
            }
        }

        try IPCPOSIX.writeAll(JSONLineCodec.appendLineDelimiter(to: line), to: descriptor)
        return try IPCPOSIX.readLine(from: descriptor, path: socketPath.rawValue, maxLineBytes: maxLineBytes)
    }

    public func openLineStream() throws -> UnixDomainSocketLineStream {
        let descriptor = try IPCPOSIX.openUnixStreamSocket()
        do {
            try IPCPOSIX.setCloseOnExec(descriptor)
            try IPCPOSIX.setSocketTimeout(timeout, on: descriptor)
            try IPCUnixSocketAddress.withSockAddr(path: socketPath.rawValue) { address, length in
                guard Darwin.connect(descriptor, address, length) == 0 else {
                    throw IPCSocketError.current(function: "connect")
                }
            }
            return UnixDomainSocketLineStream(
                descriptor: descriptor,
                socketPath: socketPath,
                maxLineBytes: maxLineBytes
            )
        } catch {
            IPCPOSIX.close(descriptor)
            throw error
        }
    }

    static func canConnect(to path: String) -> Bool {
        guard let descriptor = try? IPCPOSIX.openUnixStreamSocket() else {
            return false
        }
        defer {
            IPCPOSIX.close(descriptor)
        }

        return (try? IPCUnixSocketAddress.withSockAddr(path: path) { address, length in
            Darwin.connect(descriptor, address, length) == 0
        }) == true
    }
}

public final class UnixDomainSocketLineStream {
    public let socketPath: IPCSocketPath
    public let maxLineBytes: Int

    private var descriptor: Int32?
    private var readBuffer = Data()

    init(descriptor: Int32, socketPath: IPCSocketPath, maxLineBytes: Int) {
        self.descriptor = descriptor
        self.socketPath = socketPath
        self.maxLineBytes = maxLineBytes
    }

    deinit {
        close()
    }

    public func sendLine(_ line: Data) throws {
        guard let descriptor else {
            throw IPCSocketError.notRunning(socketPath.rawValue)
        }
        try IPCPOSIX.writeAll(JSONLineCodec.appendLineDelimiter(to: line), to: descriptor)
    }

    public func readLine() throws -> Data {
        guard let descriptor else {
            throw IPCSocketError.notRunning(socketPath.rawValue)
        }
        if let line = JSONLineCodec.popLine(from: &readBuffer) {
            return line
        }

        var chunk = [UInt8](repeating: 0, count: 4_096)
        while true {
            let count = chunk.withUnsafeMutableBytes { rawBuffer in
                Darwin.read(descriptor, rawBuffer.baseAddress, rawBuffer.count)
            }

            if count > 0 {
                readBuffer.append(contentsOf: chunk.prefix(count))
                if readBuffer.count > maxLineBytes {
                    throw IPCSocketError.lineTooLong(limit: maxLineBytes)
                }
                if let line = JSONLineCodec.popLine(from: &readBuffer) {
                    return line
                }
                continue
            }

            if count == 0 {
                throw IPCSocketError.connectionClosedBeforeLine
            }
            if errno == EINTR {
                continue
            }
            if errno == EAGAIN || errno == EWOULDBLOCK {
                throw IPCSocketError.timedOut(socketPath.rawValue)
            }
            throw IPCSocketError.current(function: "read")
        }
    }

    public func close() {
        if let descriptor {
            IPCPOSIX.close(descriptor)
            self.descriptor = nil
        }
    }
}

enum IPCUnixSocketAddress {
    static let maxPathLength = MemoryLayout.size(ofValue: sockaddr_un().sun_path)

    static func withSockAddr<T>(
        path: String,
        _ body: (UnsafePointer<sockaddr>, socklen_t) throws -> T
    ) throws -> T {
        var address = sockaddr_un()
        address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        address.sun_family = sa_family_t(AF_UNIX)

        let pathBytes = Array(path.utf8CString)
        guard pathBytes.count <= maxPathLength else {
            throw IPCSocketError.pathTooLong(path: path, limit: maxPathLength - 1)
        }

        withUnsafeMutablePointer(to: &address.sun_path) { pointer in
            pointer.withMemoryRebound(to: CChar.self, capacity: maxPathLength) { pathPointer in
                for index in pathBytes.indices {
                    pathPointer[index] = pathBytes[index]
                }
            }
        }

        return try withUnsafePointer(to: &address) { pointer in
            try pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                try body(sockaddrPointer, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
    }
}

enum IPCPOSIX {
    static func openUnixStreamSocket() throws -> Int32 {
        let descriptor = Darwin.socket(AF_UNIX, SOCK_STREAM, 0)
        guard descriptor >= 0 else {
            throw IPCSocketError.current(function: "socket")
        }
        return descriptor
    }

    static func setCloseOnExec(_ descriptor: Int32) throws {
        let flags = Darwin.fcntl(descriptor, F_GETFD)
        guard flags >= 0 else {
            throw IPCSocketError.current(function: "fcntl(F_GETFD)")
        }
        guard Darwin.fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC) >= 0 else {
            throw IPCSocketError.current(function: "fcntl(F_SETFD)")
        }
    }

    static func setNonBlocking(_ descriptor: Int32) throws {
        let flags = Darwin.fcntl(descriptor, F_GETFL)
        guard flags >= 0 else {
            throw IPCSocketError.current(function: "fcntl(F_GETFL)")
        }
        guard Darwin.fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) >= 0 else {
            throw IPCSocketError.current(function: "fcntl(F_SETFL)")
        }
    }

    static func setSocketTimeout(_ timeout: TimeInterval, on descriptor: Int32) throws {
        let seconds = max(0, Int(timeout.rounded(.down)))
        let microseconds = max(0, Int((timeout - Double(seconds)) * 1_000_000))
        var value = timeval(tv_sec: seconds, tv_usec: Int32(microseconds))

        guard Darwin.setsockopt(
            descriptor,
            SOL_SOCKET,
            SO_RCVTIMEO,
            &value,
            socklen_t(MemoryLayout<timeval>.size)
        ) == 0 else {
            throw IPCSocketError.current(function: "setsockopt(SO_RCVTIMEO)")
        }

        guard Darwin.setsockopt(
            descriptor,
            SOL_SOCKET,
            SO_SNDTIMEO,
            &value,
            socklen_t(MemoryLayout<timeval>.size)
        ) == 0 else {
            throw IPCSocketError.current(function: "setsockopt(SO_SNDTIMEO)")
        }
    }

    static func writeAll(_ data: Data, to descriptor: Int32) throws {
        try data.withUnsafeBytes { rawBuffer in
            guard let baseAddress = rawBuffer.baseAddress else {
                return
            }

            var offset = 0
            while offset < rawBuffer.count {
                let written = Darwin.write(descriptor, baseAddress.advanced(by: offset), rawBuffer.count - offset)
                if written > 0 {
                    offset += written
                    continue
                }

                guard written < 0 && errno == EINTR else {
                    throw IPCSocketError.current(function: "write")
                }
            }
        }
    }

    static func readLine(from descriptor: Int32, path: String, maxLineBytes: Int) throws -> Data {
        var buffer = Data()
        var chunk = [UInt8](repeating: 0, count: 4_096)

        while true {
            let count = chunk.withUnsafeMutableBytes { rawBuffer in
                Darwin.read(descriptor, rawBuffer.baseAddress, rawBuffer.count)
            }

            if count > 0 {
                buffer.append(contentsOf: chunk.prefix(count))
                if buffer.count > maxLineBytes {
                    throw IPCSocketError.lineTooLong(limit: maxLineBytes)
                }
                if let line = JSONLineCodec.popLine(from: &buffer) {
                    return line
                }
                continue
            }

            if count == 0 {
                throw IPCSocketError.connectionClosedBeforeLine
            }

            if errno == EINTR {
                continue
            }
            if errno == EAGAIN || errno == EWOULDBLOCK {
                throw IPCSocketError.timedOut(path)
            }
            throw IPCSocketError.current(function: "read")
        }
    }

    static func close(_ descriptor: Int32) {
        _ = Darwin.close(descriptor)
    }
}
