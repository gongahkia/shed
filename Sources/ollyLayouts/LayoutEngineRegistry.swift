import CoreGraphics
import ollyCore
import ollyKit

public enum LayoutEngineRegistryError: Error, Equatable, Sendable {
    case duplicateFactory(LayoutEngineID)
    case unknownEngine(LayoutEngineID)
    case invalidConfigType(engineID: LayoutEngineID, expected: String, actual: String)
}

public struct AnyLayoutEngine {
    public let id: LayoutEngineID
    public let displayName: String
    public let capabilities: LayoutEngineCapabilities
    private let arrangeHandler: ([WindowSnapshot], CGRect, WindowID?) -> [Placement]

    public init<Engine: LayoutEngine>(_ engine: Engine) {
        self.id = engine.id
        self.displayName = engine.displayName
        self.capabilities = engine.capabilities
        self.arrangeHandler = engine.arrange
    }

    public func arrange(windows: [WindowSnapshot], in bounds: CGRect, focus: WindowID?) -> [Placement] {
        arrangeHandler(windows, bounds, focus)
    }
}

public protocol LayoutEngineFactory {
    associatedtype Engine: LayoutEngine

    var id: LayoutEngineID { get }
    var displayName: String { get }

    func makeEngine(config: Engine.Config) throws -> Engine
}

public struct AnyLayoutEngineFactory {
    public let id: LayoutEngineID
    public let displayName: String
    private let makeEngineHandler: (Any) throws -> AnyLayoutEngine

    public init<Factory: LayoutEngineFactory>(_ factory: Factory) {
        self.id = factory.id
        self.displayName = factory.displayName
        self.makeEngineHandler = { config in
            guard let config = config as? Factory.Engine.Config else {
                throw LayoutEngineRegistryError.invalidConfigType(
                    engineID: factory.id,
                    expected: String(describing: Factory.Engine.Config.self),
                    actual: String(describing: type(of: config))
                )
            }
            return try AnyLayoutEngine(factory.makeEngine(config: config))
        }
    }

    public func makeEngine(config: Any) throws -> AnyLayoutEngine {
        try makeEngineHandler(config)
    }
}

public actor LayoutEngineRegistry {
    private var factoriesByID: [LayoutEngineID: AnyLayoutEngineFactory] = [:]

    public init(factories: [AnyLayoutEngineFactory] = []) throws {
        for factory in factories {
            guard factoriesByID[factory.id] == nil else {
                throw LayoutEngineRegistryError.duplicateFactory(factory.id)
            }
            factoriesByID[factory.id] = factory
        }
    }

    public func registeredEngineIDs() -> [LayoutEngineID] {
        factoriesByID.keys.sorted { $0.rawValue < $1.rawValue }
    }

    public func factory(for id: LayoutEngineID) -> AnyLayoutEngineFactory? {
        factoriesByID[id]
    }

    public func register<Factory: LayoutEngineFactory>(_ factory: Factory) throws {
        let erasedFactory = AnyLayoutEngineFactory(factory)
        guard factoriesByID[erasedFactory.id] == nil else {
            throw LayoutEngineRegistryError.duplicateFactory(erasedFactory.id)
        }
        factoriesByID[erasedFactory.id] = erasedFactory
    }

    public func unregister(id: LayoutEngineID) {
        factoriesByID[id] = nil
    }

    public func makeEngine(id: LayoutEngineID, config: Any) throws -> AnyLayoutEngine {
        guard let factory = factoriesByID[id] else {
            throw LayoutEngineRegistryError.unknownEngine(id)
        }
        return try factory.makeEngine(config: config)
    }
}
