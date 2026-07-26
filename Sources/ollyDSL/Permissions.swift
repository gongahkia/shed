import Foundation

/// Purpose: Controls whether shell-backed raw actions may execute.
/// Parameters: Choose `.off`, `.allowAll`, or `.allow(["label"])`.
/// Example: `shellExec(.allow(["safari"]))`
/// See also: `Permissions`, `Action.shell`.
public enum ShellExecPolicy: Codable, Equatable, Sendable {
    case off
    case allowAll
    case allow([String])

    public func allows(label: String) -> Bool {
        switch self {
        case .off:
            return false
        case .allowAll:
            return true
        case let .allow(labels):
            return labels.contains(label)
        }
    }
}

/// Purpose: Declares one permission setting inside `Permissions`.
/// Parameters: Use `shellExec` to configure raw shell execution.
/// Example: `PermissionsDirective.shellExec(.off)`
/// See also: `Permissions`, `PermissionsBuilder`.
public enum PermissionsDirective: Codable, Equatable, Sendable {
    case shellExec(ShellExecPolicy)
}

/// Purpose: Groups runtime permission settings for unsafe extension points.
/// Parameters: Pass a shell execution policy directly or use `@PermissionsBuilder`.
/// Example: `Permissions { shellExec(.allowAll) }`
/// See also: `ShellExecPolicy`, `Config`.
public struct Permissions: Codable, Equatable, Sendable {
    public let shellExec: ShellExecPolicy

    public init(shellExec: ShellExecPolicy = .off) {
        self.shellExec = shellExec
    }

    public init(@PermissionsBuilder _ build: () -> [PermissionsDirective]) {
        var shellExec = ShellExecPolicy.off
        for directive in build() {
            switch directive {
            case let .shellExec(policy):
                shellExec = policy
            }
        }
        self.init(shellExec: shellExec)
    }
}

/// Purpose: Builds permission declarations inside `Permissions { ... }`.
/// Parameters: Accepts permission directives, arrays, and conditionals.
/// Example: `Permissions { shellExec(.off) }`
/// See also: `Permissions`, `PermissionsDirective`.
@resultBuilder
public enum PermissionsBuilder {
    public static func buildBlock(_ components: [PermissionsDirective]...) -> [PermissionsDirective] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [PermissionsDirective]?) -> [PermissionsDirective] {
        component ?? []
    }

    public static func buildEither(first component: [PermissionsDirective]) -> [PermissionsDirective] {
        component
    }

    public static func buildEither(second component: [PermissionsDirective]) -> [PermissionsDirective] {
        component
    }

    public static func buildArray(_ components: [[PermissionsDirective]]) -> [PermissionsDirective] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: PermissionsDirective) -> [PermissionsDirective] {
        [expression]
    }
}

/// Purpose: Declares shell execution policy for raw actions.
/// Parameters: Pass `.off`, `.allowAll`, or `.allow(["label"])`.
/// Example: `shellExec(.allowAll)`
/// See also: `Permissions`, `ShellExecPolicy`.
public func shellExec(_ policy: ShellExecPolicy) -> PermissionsDirective {
    .shellExec(policy)
}
