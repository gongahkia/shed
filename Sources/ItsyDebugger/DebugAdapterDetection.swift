import Foundation
import ItsyEditor

public struct DebugAdapterRemediation: Equatable, Sendable {
	public var adapterID: String
	public var command: String
	public var hint: String

	public init(adapterID: String, command: String, hint: String) {
		self.adapterID = adapterID
		self.command = command
		self.hint = hint
	}
}

public enum DebugAdapterAvailability: Equatable, Sendable {
	case available(URL)
	case missing(DebugAdapterRemediation)
}

public enum DebugAdapterDetector {
	public static func availability(
		for adapter: DebugAdapterConfig,
		workspaceRoot: URL? = nil,
		environment: [String: String] = ProcessInfo.processInfo.environment,
		fileManager: FileManager = .default
	) -> DebugAdapterAvailability {
		if let component = ManagedSupportCatalog.bundled.component(id: adapter.id), component.kind == .debugAdapter,
			component.command == adapter.command, component.arguments == adapter.args {
			guard ManagedSupportEnablement.isEnabled(component) else {
				return .missing(DebugAdapterRemediation(adapterID: adapter.id, command: adapter.command, hint: "Open Language & Debugger Support in Itsy."))
			}
			if let executable = ManagedSupportResolver.executableURL(for: component, fileManager: fileManager) {
				return .available(executable)
			}
		}
		if let executable = executableURL(for: adapter.command, workspaceRoot: workspaceRoot, environment: environment, fileManager: fileManager) {
			return .available(executable)
		}
		return .missing(DebugAdapterRemediation(
			adapterID: adapter.id,
			command: adapter.command,
			hint: adapter.remediation ?? defaultHint(for: adapter.kind)
		))
	}

	private static func executableURL(
		for command: String,
		workspaceRoot: URL?,
		environment: [String: String],
		fileManager: FileManager
	) -> URL? {
		let expanded = expand(command, workspaceRoot: workspaceRoot)
		if expanded.contains("/") {
			let url = URL(fileURLWithPath: expanded).standardizedFileURL
			return fileManager.isExecutableFile(atPath: url.path) ? url : nil
		}
		let path = environment["PATH"]?.split(separator: ":").map(String.init) ?? []
		for directory in path + ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"] {
			let url = URL(fileURLWithPath: directory).appendingPathComponent(expanded).standardizedFileURL
			if fileManager.isExecutableFile(atPath: url.path) {
				return url
			}
		}
		return nil
	}

	private static func expand(_ command: String, workspaceRoot: URL?) -> String {
		let workspace = workspaceRoot?.standardizedFileURL.path ?? "${workspaceFolder}"
		return NSString(string: command.replacingOccurrences(of: "${workspaceFolder}", with: workspace)).expandingTildeInPath
	}

	private static func defaultHint(for kind: DebugAdapterKind) -> String {
		switch kind {
		case .lldb:
			return "Install Xcode Command Line Tools to provide lldb-dap."
		case .debugpy:
			return "Open Language & Debugger Support in Itsy."
		case .jsDebug:
			return "Open Language & Debugger Support in Itsy."
		case .delve:
			return "Open Language & Debugger Support in Itsy."
		case .codeLLDB:
			return "Open Language & Debugger Support in Itsy."
		case .custom:
			return "Configure an executable adapter command in .itsy/dap.toml."
		}
	}
}
