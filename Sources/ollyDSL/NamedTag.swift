import Foundation
import ollyCore

public enum WorkspacesError: Error, Equatable, Sendable {
    case duplicateTagName(String)
    case tooManyTags(Int)
}

public struct NamedTagDeclaration: Codable, Equatable, Sendable {
    public let name: String

    public init(_ name: StaticString) {
        self.name = String(describing: name)
    }

    init(uncheckedName name: String) {
        self.name = name
    }
}

public struct NamedTag: Codable, Equatable, Sendable {
    public let name: String
    public let tag: Tag

    public init(name: String, tag: Tag) {
        self.name = name
        self.tag = tag
    }
}

public extension Tag {
    static func named(_ name: StaticString) -> NamedTagDeclaration {
        NamedTagDeclaration(name)
    }
}

public struct Workspaces: Codable, Equatable, Sendable {
    public let tags: [NamedTag]

    public init(_ tags: [NamedTag] = []) {
        self.tags = tags
    }

    public init(@WorkspacesBuilder _ build: () -> [NamedTagDeclaration]) {
        do {
            self = try Workspaces(validating: build())
        } catch {
            preconditionFailure(String(describing: error))
        }
    }

    public init(validating declarations: [NamedTagDeclaration]) throws {
        guard declarations.count <= 64 else {
            throw WorkspacesError.tooManyTags(declarations.count)
        }

        var seenNames = Set<String>()
        var tags: [NamedTag] = []
        for (index, declaration) in declarations.enumerated() {
            guard seenNames.insert(declaration.name).inserted else {
                throw WorkspacesError.duplicateTagName(declaration.name)
            }
            tags.append(NamedTag(name: declaration.name, tag: try Tag(index: index)))
        }
        self.tags = tags
    }

    public func tag(named name: String) -> Tag? {
        tags.first { $0.name == name }?.tag
    }
}

@resultBuilder
public enum WorkspacesBuilder {
    public static func buildBlock(_ components: [NamedTagDeclaration]...) -> [NamedTagDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [NamedTagDeclaration]?) -> [NamedTagDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [NamedTagDeclaration]) -> [NamedTagDeclaration] {
        component
    }

    public static func buildEither(second component: [NamedTagDeclaration]) -> [NamedTagDeclaration] {
        component
    }

    public static func buildArray(_ components: [[NamedTagDeclaration]]) -> [NamedTagDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: NamedTagDeclaration) -> [NamedTagDeclaration] {
        [expression]
    }
}
