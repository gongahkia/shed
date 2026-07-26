import Foundation

public struct IPCRequestEnvelope: Codable, Equatable, Sendable {
    public let version: Int
    public let id: String?
    public let command: IPCCommand

    public init(version: Int = OllyIPC.protocolVersion, id: String? = nil, command: IPCCommand) {
        self.version = version
        self.id = id
        self.command = command
    }

    public func newlineDelimitedJSON(encoder: JSONEncoder = JSONEncoder()) throws -> Data {
        try JSONLineCodec.encodeLine(self, encoder: encoder)
    }
}

public enum IPCResponseStatus: String, Codable, Equatable, Sendable {
    case success = "ok"
    case error
}

public struct IPCResponseEnvelope: Codable, Equatable, Sendable {
    public let version: Int
    public let id: String?
    public let status: IPCResponseStatus
    public let result: IPCCommandResult?
    public let error: IPCErrorPayload?

    public init(
        version: Int = OllyIPC.protocolVersion,
        id: String? = nil,
        status: IPCResponseStatus,
        result: IPCCommandResult? = nil,
        error: IPCErrorPayload? = nil
    ) {
        self.version = version
        self.id = id
        self.status = status
        self.result = result
        self.error = error
    }

    public static func ok(id: String? = nil, result: IPCCommandResult? = nil) -> IPCResponseEnvelope {
        IPCResponseEnvelope(id: id, status: .success, result: result)
    }

    public static func failure(id: String? = nil, error: IPCErrorPayload) -> IPCResponseEnvelope {
        IPCResponseEnvelope(id: id, status: .error, error: error)
    }

    public func newlineDelimitedJSON(encoder: JSONEncoder = JSONEncoder()) throws -> Data {
        try JSONLineCodec.encodeLine(self, encoder: encoder)
    }
}

public struct IPCErrorPayload: Codable, Equatable, Sendable {
    public let code: String
    public let message: String

    public init(code: String, message: String) {
        self.code = code
        self.message = message
    }
}
