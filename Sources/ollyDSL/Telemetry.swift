import Foundation

/// Purpose: Represents one local crash telemetry directive.
/// Parameters: Use enabled, endpoint, or scrubbed-bundle-id controls.
/// Example: `Telemetry { enabled(false); endpoint(nil); scrubbedBundleIDs(true) }`
/// See also: `Telemetry`, `ConfigSection`.
public enum TelemetryDirective: Codable, Equatable, Sendable {
    case enabled(Bool)
    case endpoint(String?)
    case scrubbedBundleIDs(Bool)
    case usageEndpoint(String?)
}

/// Purpose: Configures local crash capture and explicit crash-report upload.
/// Parameters: Keep enabled false by default and set a self-hosted endpoint before flushing.
/// Example: `Telemetry { enabled(true); endpoint("https://example.test/crashes") }`
/// See also: `Config`, `TelemetryBuilder`.
public struct Telemetry: Codable, Equatable, Sendable {
    public let enabled: Bool
    public let endpoint: String?
    public let scrubbedBundleIDs: Bool
    public let usageEndpoint: String?

    public init(
        enabled: Bool = false,
        endpoint: String? = nil,
        scrubbedBundleIDs: Bool = true,
        usageEndpoint: String? = nil
    ) {
        self.enabled = enabled
        self.endpoint = endpoint
        self.scrubbedBundleIDs = scrubbedBundleIDs
        self.usageEndpoint = usageEndpoint
    }

    public init(@TelemetryBuilder _ build: () -> [TelemetryDirective]) {
        var enabled = false
        var endpoint: String?
        var scrubbedBundleIDs = true
        var usageEndpoint: String?
        for directive in build() {
            switch directive {
            case let .enabled(value):
                enabled = value
            case let .endpoint(value):
                endpoint = value
            case let .scrubbedBundleIDs(value):
                scrubbedBundleIDs = value
            case let .usageEndpoint(value):
                usageEndpoint = value
            }
        }
        self.init(
            enabled: enabled,
            endpoint: endpoint,
            scrubbedBundleIDs: scrubbedBundleIDs,
            usageEndpoint: usageEndpoint
        )
    }
}

/// Purpose: Builds telemetry declarations inside `Telemetry { ... }`.
/// Parameters: Accepts telemetry directive expressions.
/// Example: `Telemetry { enabled(false) }`
/// See also: `Telemetry`, `TelemetryDirective`.
@resultBuilder
public enum TelemetryBuilder {
    public static func buildBlock(_ components: TelemetryDirective...) -> [TelemetryDirective] {
        components
    }

    public static func buildExpression(_ expression: TelemetryDirective) -> TelemetryDirective {
        expression
    }
}

/// Purpose: Enables or disables local crash capture.
/// Parameters: Pass false to keep the default no-telemetry posture.
/// Example: `enabled(false)`
/// See also: `Telemetry`, `endpoint(_:)`.
public func enabled(_ value: Bool) -> TelemetryDirective {
    .enabled(value)
}

/// Purpose: Sets the explicit flush endpoint for crash JSON uploads.
/// Parameters: Pass a self-hosted HTTPS URL string or nil.
/// Example: `endpoint("https://example.test/olly/crashes")`
/// See also: `Telemetry`, `enabled(_:)`.
public func endpoint(_ value: String?) -> TelemetryDirective {
    .endpoint(value)
}

/// Purpose: Controls whether bundle identifiers are scrubbed from future crash payloads.
/// Parameters: Keep true unless explicitly debugging app-specific crashes.
/// Example: `scrubbedBundleIDs(true)`
/// See also: `Telemetry`, `enabled(_:)`.
public func scrubbedBundleIDs(_ value: Bool) -> TelemetryDirective {
    .scrubbedBundleIDs(value)
}

/// Purpose: Sets the explicit aggregate usage telemetry endpoint.
/// Parameters: Pass a self-hosted HTTPS URL string or nil.
/// Example: `usageEndpoint("https://example.test/olly/usage")`
/// See also: `Telemetry`, `endpoint(_:)`.
public func usageEndpoint(_ value: String?) -> TelemetryDirective {
    .usageEndpoint(value)
}

public extension ConfigBuilder {
    static func buildExpression(_ expression: Telemetry) -> [ConfigSection] {
        [.telemetry(expression)]
    }
}
