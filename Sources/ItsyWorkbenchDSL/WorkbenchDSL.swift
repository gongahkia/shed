import Foundation
import ItsyWorkbenchLayout

public enum WorkbenchProfileBuilder {
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
