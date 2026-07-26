import ItsyDAP
import ItsyDebugger
import Testing

@Test func coreDebugAdapterCapabilityMatrixMatchesCoreContracts() {
	#expect(DebugAdapterCapabilityMatrix.core == [
		.init(adapterID: "debugpy", languageIDs: ["python"]),
		.init(adapterID: "lldb-dap", languageIDs: ["c", "cpp"]),
		.init(adapterID: "vscode-js-debug", languageIDs: ["javascript", "typescript"]),
	])
	#expect(DebugAdapterCapabilityMatrix.validationErrors().isEmpty)
}

@Test func debugAdapterCapabilitiesRequireExplicitDAPAdvertisement() {
	let capabilities = DAPCapabilities(
		supportsConfigurationDoneRequest: true,
		supportsStepBack: true,
		supportsReverseContinue: false,
		supportsSetVariable: true,
		supportsRestartRequest: nil,
		supportsTerminateRequest: true
	)
	#expect(DebugAdapterCapabilityMatrix.negotiatedCapabilities(from: capabilities) == [.configurationDone, .setVariable, .stepBack, .terminate])
}

@Test func debugAdapterCapabilityMatrixRejectsInvalidContracts() {
	let invalid = DebugAdapterCapabilityMatrixRow(
		adapterID: "missing-adapter",
		languageIDs: ["python"],
		negotiatedCapabilities: []
	)
	let errors = DebugAdapterCapabilityMatrix.validationErrors(for: [invalid, invalid])
	#expect(errors.contains(.duplicateAdapterID("missing-adapter")))
	#expect(errors.contains(.missingNegotiatedCapability(adapterID: "missing-adapter", capability: .configurationDone)))
	#expect(errors.contains(.invalidAdapterID("missing-adapter")))
	#expect(errors.contains(.uncoveredCoreLanguageID("python")))
}
