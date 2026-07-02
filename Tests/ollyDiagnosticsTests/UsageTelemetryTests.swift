import XCTest
import ollyDiagnostics

final class UsageTelemetryTests: XCTestCase {
    func testConsentStorePersistsUserDefaultsKey() throws {
        let defaults = try defaults()
        let store = UsageTelemetryConsentStore(defaults: defaults)

        XCTAssertEqual(store.read(), .undecided)

        store.write(.optIn)

        XCTAssertEqual(defaults.string(forKey: UsageTelemetryConsentStore.key), "opt-in")
        XCTAssertEqual(store.read(), .optIn)
    }

    func testRuntimeSettingsRequireOptInEndpointAndEnabledEnvironment() throws {
        let defaults = try defaults()
        let store = UsageTelemetryConsentStore(defaults: defaults)

        var settings = UsageTelemetryRuntimeSettings(
            configUsageEndpoint: "https://example.test/usage",
            consentStore: store,
            environment: [:]
        )
        XCTAssertFalse(settings.canUpload)

        store.write(.optIn)
        settings = UsageTelemetryRuntimeSettings(
            configUsageEndpoint: nil,
            consentStore: store,
            environment: [:]
        )
        XCTAssertFalse(settings.canUpload)

        settings = UsageTelemetryRuntimeSettings(
            configUsageEndpoint: "https://example.test/usage",
            consentStore: store,
            environment: [:]
        )
        XCTAssertTrue(settings.canUpload)

        settings = UsageTelemetryRuntimeSettings(
            configUsageEndpoint: "https://example.test/usage",
            consentStore: store,
            environment: ["OLLY_DISABLE_TELEMETRY": "1"]
        )
        XCTAssertFalse(settings.canUpload)
        XCTAssertTrue(settings.isDisabledByEnvironment)
    }

    func testPayloadContainsOnlyAggregateUsageFields() throws {
        let payload = UsageTelemetryPayload(
            generatedAt: Date(timeIntervalSince1970: 100),
            context: UsageTelemetryContext(
                appVersion: "1.0",
                dslVersion: "v1",
                displayCount: 2,
                tagCount: 6
            ),
            enginesUsed: ["grid", "bsp", "grid"],
            sessionDurationSeconds: 12.5
        )

        XCTAssertEqual(payload.enginesUsed, ["bsp", "grid"])
        XCTAssertEqual(payload.sessionDurationSeconds, 12.5)

        let json = try XCTUnwrap(String(data: UsageTelemetry.payloadData(payload), encoding: .utf8))
        XCTAssertTrue(json.contains("displayCount"))
        XCTAssertTrue(json.contains("tagCount"))
        XCTAssertTrue(json.contains("enginesUsed"))
        XCTAssertFalse(json.localizedCaseInsensitiveContains("bundle"))
        XCTAssertFalse(json.localizedCaseInsensitiveContains("title"))
        XCTAssertFalse(json.localizedCaseInsensitiveContains("frame"))
        XCTAssertFalse(json.localizedCaseInsensitiveContains("window"))
        XCTAssertFalse(json.localizedCaseInsensitiveContains("identifier"))
    }

    func testUploadUsesProvidedEndpoint() throws {
        let uploader = RecordingUsageUploader()
        let endpoint = try XCTUnwrap(URL(string: "https://example.test/usage"))
        let payload = UsageTelemetryPayload(
            context: UsageTelemetryContext(appVersion: "1", dslVersion: "v1", displayCount: 1, tagCount: 1),
            enginesUsed: ["floating"],
            sessionDurationSeconds: 1
        )

        try UsageTelemetry.upload(payload: payload, to: endpoint, uploader: uploader)

        XCTAssertEqual(uploader.uploads.map(\.endpoint), [endpoint])
        XCTAssertEqual(uploader.uploads.first?.data, try UsageTelemetry.payloadData(payload))
    }

    private func defaults() throws -> UserDefaults {
        let suiteName = "olly-usage-telemetry-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        addTeardownBlock {
            defaults.removePersistentDomain(forName: suiteName)
        }
        return defaults
    }
}

private final class RecordingUsageUploader: UsageTelemetryUploading {
    private(set) var uploads: [(data: Data, endpoint: URL)] = []

    func upload(payloadData: Data, to endpoint: URL) throws {
        uploads.append((payloadData, endpoint))
    }
}
