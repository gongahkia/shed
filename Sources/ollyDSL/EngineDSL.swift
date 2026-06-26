// swiftlint:disable inclusive_language
import Foundation
import ollyCore
import ollyLayouts

public struct EngineDeclaration: Codable, Equatable, Sendable {
    public let id: LayoutEngineID

    public init(_ id: LayoutEngineID) {
        self.id = id
    }
}

public extension EngineDeclaration {
    static let floating = EngineDeclaration(FloatingLayoutEngine.engineID)
    static let masterStack = EngineDeclaration(MasterStackLayoutEngine.engineID)
    static let manual = EngineDeclaration(ManualLayoutEngine.engineID)
    static let bsp = EngineDeclaration(BSPLayoutEngine.engineID)
    static let niriScroll = EngineDeclaration(NiriScrollLayoutEngine.engineID)
}

public struct Engines: Codable, Equatable, Sendable {
    public let engines: [EngineDeclaration]

    public init(_ engines: [EngineDeclaration] = []) {
        self.engines = engines
    }

    public init(@EngineBuilder _ build: () -> [EngineDeclaration]) {
        self.engines = build()
    }
}

@resultBuilder
public enum EngineBuilder {
    public static func buildBlock(_ components: [EngineDeclaration]...) -> [EngineDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [EngineDeclaration]?) -> [EngineDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [EngineDeclaration]) -> [EngineDeclaration] {
        component
    }

    public static func buildEither(second component: [EngineDeclaration]) -> [EngineDeclaration] {
        component
    }

    public static func buildArray(_ components: [[EngineDeclaration]]) -> [EngineDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: EngineDeclaration) -> [EngineDeclaration] {
        [expression]
    }
}
// swiftlint:enable inclusive_language
