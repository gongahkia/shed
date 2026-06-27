import Foundation
import ollyCore

/// Purpose: Reports invalid workspace tag declarations before they become runtime state.
/// Parameters: Inspect the associated duplicate name or tag count.
/// Example: `XCTAssertThrowsError(try Workspaces(validating: declarations))`
/// See also: `Workspaces`, `NamedTagDeclaration`.
public enum WorkspacesError: Error, Equatable, Sendable {
    case duplicateTagName(String)
    case tooManyTags(Int)
}

/// Purpose: Captures a user-facing workspace tag name before assigning its numeric tag.
/// Parameters: Pass a static tag name.
/// Example: `Tag.named("web")`
/// See also: `NamedTag`, `Workspaces`.
public struct NamedTagDeclaration: Codable, Equatable, Sendable {
    public let name: String

    public init(_ name: StaticString) {
        self.name = String(describing: name)
    }

    init(uncheckedName name: String) {
        self.name = name
    }
}

/// Purpose: Binds a display name to a concrete River-style tag bit.
/// Parameters: Pass the visible name and assigned `Tag`.
/// Example: `NamedTag(name: "code", tag: try Tag(index: 1))`
/// See also: `NamedTagDeclaration`, `Workspaces`.
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

/// Purpose: Groups named workspace tags for the top-level config.
/// Parameters: Pass resolved tags or build named tag declarations.
/// Example: `Workspaces { Tag.named("web"); Tag.named("code") }`
/// See also: `NamedTagDeclaration`, `WorkspacesBuilder`.
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
/// Purpose: Builds named tag declarations inside `Workspaces { ... }`.
/// Parameters: Accepts `NamedTagDeclaration` expressions, arrays, and conditionals.
/// Example: `Workspaces { Tag.named("chat") }`
/// See also: `Workspaces`, `NamedTagDeclaration`.
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
