/// Purpose: Builds named tag declarations inside `Workspaces { ... }`.
/// Parameters: Accepts `NamedTagDeclaration` expressions, arrays, and conditionals.
/// Example: `Workspaces { Tag.named("chat") }`
/// See also: `Workspaces`, `NamedTagDeclaration`.
@resultBuilder
public enum WorkspacesBuilder {
    public static func buildBlock(_ components: [WorkspaceDeclaration]...) -> [WorkspaceDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [WorkspaceDeclaration]?) -> [WorkspaceDeclaration] {
        component ?? []
    }

    public static func buildEither(first component: [WorkspaceDeclaration]) -> [WorkspaceDeclaration] {
        component
    }

    public static func buildEither(second component: [WorkspaceDeclaration]) -> [WorkspaceDeclaration] {
        component
    }

    public static func buildArray(_ components: [[WorkspaceDeclaration]]) -> [WorkspaceDeclaration] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: NamedTagDeclaration) -> [WorkspaceDeclaration] {
        [.tag(expression)]
    }

    public static func buildExpression(_ expression: DisplayWorkspaceDeclaration) -> [WorkspaceDeclaration] {
        [.display(expression)]
    }
}

@resultBuilder
public enum DisplayWorkspacesBuilder {
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
