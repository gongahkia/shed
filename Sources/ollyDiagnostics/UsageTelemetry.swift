import Foundation

public enum UsageTelemetryError: Error, CustomStringConvertible, Equatable {
    case uploadFailed(String)

    public var description: String {
        switch self {
        case let .uploadFailed(message):
            return "usage telemetry upload failed: \(message)"
        }
    }
}

public enum UsageTelemetryConsent: String, Codable, Equatable, Sendable {
    case optIn = "opt-in"
    case disabled
    case undecided
}

public struct UsageTelemetryConsentStore {
    public static let key = "olly.telemetry.consent"

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func read() -> UsageTelemetryConsent {
        guard let rawValue = defaults.string(forKey: Self.key),
              let consent = UsageTelemetryConsent(rawValue: rawValue) else {
            return .undecided
        }
        return consent
    }

    public func write(_ consent: UsageTelemetryConsent) {
        defaults.set(consent.rawValue, forKey: Self.key)
    }
}

public struct UsageTelemetryRuntimeSettings: Equatable, Sendable {
    public let consent: UsageTelemetryConsent
    public let endpoint: URL?
    public let isDisabledByEnvironment: Bool

    public init(
        consent: UsageTelemetryConsent = .undecided,
        endpoint: URL? = nil,
        isDisabledByEnvironment: Bool = false
    ) {
        self.consent = consent
        self.endpoint = endpoint
        self.isDisabledByEnvironment = isDisabledByEnvironment
    }

    public init(
        configUsageEndpoint: String?,
        consentStore: UsageTelemetryConsentStore = UsageTelemetryConsentStore(),
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) {
        self.init(
            consent: consentStore.read(),
            endpoint: configUsageEndpoint.flatMap(URL.init(string:)),
            isDisabledByEnvironment: TelemetryEnvironment.isDisabled(environment)
        )
    }

    public var canUpload: Bool {
        !isDisabledByEnvironment && consent == .optIn && endpoint != nil
    }
}

public struct UsageTelemetryContext: Equatable, Sendable {
    public let appVersion: String
    public let dslVersion: String
    public let displayCount: Int
    public let tagCount: Int

    public init(appVersion: String, dslVersion: String, displayCount: Int, tagCount: Int) {
        self.appVersion = appVersion
        self.dslVersion = dslVersion
        self.displayCount = displayCount
        self.tagCount = tagCount
    }
}

public struct UsageTelemetryPayload: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let generatedAt: Date
    public let appVersion: String
    public let dslVersion: String
    public let displayCount: Int
    public let tagCount: Int
    public let enginesUsed: [String]
    public let sessionDurationSeconds: Double

    public init(
        schemaVersion: Int = 1,
        generatedAt: Date = Date(),
        context: UsageTelemetryContext,
        enginesUsed: [String],
        sessionDurationSeconds: TimeInterval
    ) {
        self.schemaVersion = schemaVersion
        self.generatedAt = generatedAt
        appVersion = context.appVersion
        dslVersion = context.dslVersion
        displayCount = context.displayCount
        tagCount = context.tagCount
        self.enginesUsed = Array(Set(enginesUsed)).sorted()
        self.sessionDurationSeconds = max(0, sessionDurationSeconds)
    }
}

public protocol UsageTelemetryUploading {
    func upload(payloadData: Data, to endpoint: URL) throws
}

public enum UsageTelemetry {
    public static func payloadData(_ payload: UsageTelemetryPayload) throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(payload)
    }

    public static func upload(
        payload: UsageTelemetryPayload,
        to endpoint: URL,
        uploader: UsageTelemetryUploading = URLSessionUsageTelemetryUploader()
    ) throws {
        try uploader.upload(payloadData: payloadData(payload), to: endpoint)
    }
}

public struct URLSessionUsageTelemetryUploader: UsageTelemetryUploading {
    public let timeout: TimeInterval

    public init(timeout: TimeInterval = 3) {
        self.timeout = timeout
    }

    public func upload(payloadData: Data, to endpoint: URL) throws {
        var request = URLRequest(url: endpoint, timeoutInterval: timeout)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Void, Error>?
        let task = URLSession.shared.uploadTask(with: request, from: payloadData) { _, response, error in
            if let error {
                result = .failure(error)
            } else if let response = response as? HTTPURLResponse,
                      !(200..<300).contains(response.statusCode) {
                result = .failure(UsageTelemetryError.uploadFailed("HTTP \(response.statusCode)"))
            } else {
                result = .success(())
            }
            semaphore.signal()
        }
        task.resume()
        if semaphore.wait(timeout: .now() + timeout) == .timedOut {
            task.cancel()
            throw UsageTelemetryError.uploadFailed("timed out")
        }
        switch result {
        case .success:
            return
        case let .failure(error):
            throw error
        case .none:
            throw UsageTelemetryError.uploadFailed("no response")
        }
    }
}
