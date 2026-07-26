import Foundation
import ItsyWorkbenchLayout

public enum WorkbenchSplitAxis: String, Equatable, Sendable {
	case horizontal
	case vertical
}

public indirect enum WorkbenchNode: Equatable, Sendable {
	case component(WorkbenchComponentID)
	case split(axis: WorkbenchSplitAxis, children: [WorkbenchNode])
}

public struct WorkbenchProfileDefinition: Equatable, Sendable {
	public var configuration: WorkbenchLayoutConfiguration
	public var root: WorkbenchNode

	public init(configuration: WorkbenchLayoutConfiguration, root: WorkbenchNode) {
		self.configuration = configuration
		self.root = root
	}
}

public enum WorkbenchProfileBuilder {
	public static func definition(for profile: WorkbenchProfile) -> WorkbenchProfileDefinition {
		switch profile {
		case .workbench:
			return .init(configuration: workbench(), root: .split(axis: .horizontal, children: [
				.component(.fileTree),
				.split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]),
				.component(.git),
			]))
		case .focus:
			return .init(configuration: focus(), root: .split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]))
		case .review:
			return .init(configuration: review(), root: .split(axis: .horizontal, children: [
				.split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]),
				.component(.git),
			]))
		}
	}

	public static func workbench() -> WorkbenchLayoutConfiguration {
		.init(profile: .workbench)
	}

	public static func focus() -> WorkbenchLayoutConfiguration {
		.init(profile: .focus, fileTree: .hidden)
	}

	public static func review() -> WorkbenchLayoutConfiguration {
		.init(profile: .review, fileTree: .hidden, git: .visible)
	}
}

public enum WorkbenchConfigurationValidator {
	public static func validate(_ configuration: WorkbenchLayoutConfiguration) -> String? {
		if configuration.profile == .focus, configuration.fileTree == .visible {
			return "workbench.profile = \"focus\" cannot force file_tree = \"visible\""
		}
		if configuration.profile == .review, configuration.fileTree == .visible {
			return "workbench.profile = \"review\" cannot force file_tree = \"visible\""
		}
		return nil
	}
}
