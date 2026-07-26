import ItsyDAP
import ItsyEditor

public enum DebugAdapterCapability: String, CaseIterable, Codable, Equatable, Hashable, Sendable {
	case configurationDone = "configuration_done"
	case conditionalBreakpoints = "conditional_breakpoints"
	case hitConditionalBreakpoints = "hit_conditional_breakpoints"
	case logPoints = "log_points"
	case setVariable = "set_variable"
	case stepBack = "step_back"
	case reverseContinue = "reverse_continue"
	case restart = "restart"
	case terminate = "terminate"

	public func isSupported(by capabilities: DAPCapabilities) -> Bool {
		switch self {
		case .configurationDone:
			return capabilities.supportsConfigurationDoneRequest == true
		case .conditionalBreakpoints:
			return capabilities.supportsConditionalBreakpoints == true
		case .hitConditionalBreakpoints:
			return capabilities.supportsHitConditionalBreakpoints == true
		case .logPoints:
			return capabilities.supportsLogPoints == true
		case .setVariable:
			return capabilities.supportsSetVariable == true
		case .stepBack:
			return capabilities.supportsStepBack == true
		case .reverseContinue:
			return capabilities.supportsReverseContinue == true
		case .restart:
			return capabilities.supportsRestartRequest == true
		case .terminate:
			return capabilities.supportsTerminateRequest == true
		}
	}
}

public struct DebugAdapterCapabilityMatrixRow: Equatable, Sendable {
	public var adapterID: String
	public var languageIDs: Set<String>
	public var negotiatedCapabilities: Set<DebugAdapterCapability>

	public init(adapterID: String, languageIDs: Set<String>, negotiatedCapabilities: Set<DebugAdapterCapability> = Set(DebugAdapterCapability.allCases)) {
		self.adapterID = adapterID
		self.languageIDs = languageIDs
		self.negotiatedCapabilities = negotiatedCapabilities
	}
}

public enum DebugAdapterCapabilityMatrixValidationError: Error, Equatable, Sendable {
	case duplicateAdapterID(String)
	case missingNegotiatedCapability(adapterID: String, capability: DebugAdapterCapability)
	case invalidAdapterID(String)
	case invalidLanguageID(adapterID: String, languageID: String)
	case uncoveredCoreLanguageID(String)
}

public enum DebugAdapterCapabilityMatrix {
	public static let core: [DebugAdapterCapabilityMatrixRow] = [
		.init(adapterID: "debugpy", languageIDs: ["python"]),
		.init(adapterID: "lldb-dap", languageIDs: ["c", "cpp"]),
		.init(adapterID: "vscode-js-debug", languageIDs: ["javascript", "typescript"]),
	]

	public static func negotiatedCapabilities(from capabilities: DAPCapabilities) -> Set<DebugAdapterCapability> {
		Set(DebugAdapterCapability.allCases.filter { $0.isSupported(by: capabilities) })
	}

	public static func validationErrors(
		for rows: [DebugAdapterCapabilityMatrixRow] = core,
		registry: DebugAdapterRegistry = .init(),
		catalog: ManagedSupportCatalog = .bundled,
		languageMatrix: [SupportedLanguageCapabilityRow] = SupportedLanguageCapabilityMatrix.core
	) -> [DebugAdapterCapabilityMatrixValidationError] {
		var errors: [DebugAdapterCapabilityMatrixValidationError] = []
		var adapterIDs: Set<String> = []
		var coveredLanguageIDs: Set<String> = []
		for row in rows {
			if !adapterIDs.insert(row.adapterID).inserted {
				errors.append(.duplicateAdapterID(row.adapterID))
			}
			for capability in DebugAdapterCapability.allCases where !row.negotiatedCapabilities.contains(capability) {
				errors.append(.missingNegotiatedCapability(adapterID: row.adapterID, capability: capability))
			}
			guard registry.adapter(id: row.adapterID) != nil,
			      let component = catalog.component(id: row.adapterID),
			      component.kind == .debugAdapter,
			      component.tier == .core
			else {
				errors.append(.invalidAdapterID(row.adapterID))
				continue
			}
			for languageID in row.languageIDs {
				guard component.languageIDs.contains(languageID),
				      languageMatrix.contains(where: {
						$0.languageID == languageID &&
						$0.capabilities.contains(.debugAdapter) &&
						$0.debugAdapterID == row.adapterID
					})
				else {
					errors.append(.invalidLanguageID(adapterID: row.adapterID, languageID: languageID))
					continue
				}
				coveredLanguageIDs.insert(languageID)
			}
		}
		for row in languageMatrix where row.capabilities.contains(.debugAdapter) && !coveredLanguageIDs.contains(row.languageID) {
			errors.append(.uncoveredCoreLanguageID(row.languageID))
		}
		return errors
	}
}
