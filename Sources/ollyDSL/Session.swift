import Foundation

public enum SessionDirective: Codable, Equatable, Sendable {
    case restoreOnLaunch(Bool)
}

public struct Session: Codable, Equatable, Sendable {
    public let restoreOnLaunch: Bool

    public init(restoreOnLaunch: Bool = false) {
        self.restoreOnLaunch = restoreOnLaunch
    }

    public init(@SessionBuilder _ build: () -> [SessionDirective]) {
        var restoreOnLaunch = false
        for directive in build() {
            switch directive {
            case let .restoreOnLaunch(value):
                restoreOnLaunch = value
            }
        }
        self.init(restoreOnLaunch: restoreOnLaunch)
    }
}

@resultBuilder
public enum SessionBuilder {
    public static func buildBlock(_ components: [SessionDirective]...) -> [SessionDirective] {
        components.flatMap { $0 }
    }

    public static func buildOptional(_ component: [SessionDirective]?) -> [SessionDirective] {
        component ?? []
    }

    public static func buildEither(first component: [SessionDirective]) -> [SessionDirective] {
        component
    }

    public static func buildEither(second component: [SessionDirective]) -> [SessionDirective] {
        component
    }

    public static func buildArray(_ components: [[SessionDirective]]) -> [SessionDirective] {
        components.flatMap { $0 }
    }

    public static func buildExpression(_ expression: SessionDirective) -> [SessionDirective] {
        [expression]
    }
}

public func restoreOnLaunch(_ value: Bool) -> SessionDirective {
    .restoreOnLaunch(value)
}

public extension ConfigBuilder {
    static func buildExpression(_ expression: Session) -> [ConfigSection] {
        [.session(expression)]
    }
}
