import Foundation

public enum DSLVersion: String, Codable, Equatable, Sendable {
    case v1 // swiftlint:disable:this identifier_name
}

public struct Config: Codable, Equatable, Sendable {
    public let version: DSLVersion

    public init(version: DSLVersion = .v1) {
        self.version = version
    }

    public init(version: DSLVersion = .v1, _ build: () -> Void) {
        build()
        self.version = version
    }
}
