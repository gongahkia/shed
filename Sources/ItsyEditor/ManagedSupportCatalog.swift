import Foundation

public enum ManagedSupportTier: String, Codable, CaseIterable, Sendable {
	case core
	case onDemand = "on_demand"
	case officialLink = "official_link"
}

public enum ManagedSupportKind: String, Codable, CaseIterable, Sendable {
	case languageServer = "language_server"
	case debugAdapter = "debug_adapter"
}

public enum ManagedSupportInstallMode: String, Codable, Equatable, Sendable {
	case managed
	case system
	case officialLink = "official_link"
}

public enum ManagedSupportArchitecture: String, Codable, CaseIterable, Sendable {
	case arm64
	case x86_64

	public static var current: ManagedSupportArchitecture? {
		#if arch(arm64)
		.arm64
		#elseif arch(x86_64)
		.x86_64
		#else
		nil
		#endif
	}
}

public struct ManagedSupportArtifacts: Codable, Equatable, Sendable {
	public var arm64: ManagedSupportArtifact?
	public var x86_64: ManagedSupportArtifact?

	public init(arm64: ManagedSupportArtifact? = nil, x86_64: ManagedSupportArtifact? = nil) {
		self.arm64 = arm64
		self.x86_64 = x86_64
	}

	public func artifact(for architecture: ManagedSupportArchitecture = .current ?? .arm64) -> ManagedSupportArtifact? {
		switch architecture {
		case .arm64: arm64
		case .x86_64: x86_64
		}
	}
}

public enum ManagedSupportEnablement {
	private static let prefix = "itsy.support.enabled."

	public static func isEnabled(_ component: ManagedSupportComponent, defaults: UserDefaults = .standard) -> Bool {
		component.tier == .core || defaults.bool(forKey: prefix + component.id)
	}

	public static func setEnabled(_ enabled: Bool, for component: ManagedSupportComponent, defaults: UserDefaults = .standard) {
		defaults.set(enabled, forKey: prefix + component.id)
	}
}

public struct ManagedSupportComponent: Codable, Equatable, Sendable, Identifiable {
	public var id: String
	public var displayName: String
	public var kind: ManagedSupportKind
	public var tier: ManagedSupportTier
	public var languageIDs: [String]
	public var command: String
	public var arguments: [String]
	public var installMode: ManagedSupportInstallMode
	public var officialURL: URL
	public var systemInstallHint: String?
	public var artifacts: ManagedSupportArtifacts

	public init(
		id: String,
		displayName: String,
		kind: ManagedSupportKind,
		tier: ManagedSupportTier,
		languageIDs: [String],
		command: String,
		arguments: [String] = [],
		installMode: ManagedSupportInstallMode,
		officialURL: URL,
		systemInstallHint: String? = nil,
		artifacts: ManagedSupportArtifacts = .init()
	) {
		self.id = id
		self.displayName = displayName
		self.kind = kind
		self.tier = tier
		self.languageIDs = languageIDs
		self.command = command
		self.arguments = arguments
		self.installMode = installMode
		self.officialURL = officialURL
		self.systemInstallHint = systemInstallHint
		self.artifacts = artifacts
	}
}

public struct ManagedSupportCatalog: Codable, Equatable, Sendable {
	public var schemaVersion: Int
	public var components: [ManagedSupportComponent]

	public init(schemaVersion: Int = 1, components: [ManagedSupportComponent]) {
		self.schemaVersion = schemaVersion
		self.components = components.sorted { $0.id < $1.id }
	}

	public func component(id: String) -> ManagedSupportComponent? {
		components.first { $0.id == id }
	}

	public func component(languageID: String, kind: ManagedSupportKind) -> ManagedSupportComponent? {
		components.first { $0.kind == kind && $0.languageIDs.contains(languageID) }
	}

	public func component(command: String, kind: ManagedSupportKind) -> ManagedSupportComponent? {
		components.first { $0.kind == kind && $0.command == command }
	}

	public var coreComponents: [ManagedSupportComponent] {
		components.filter { $0.tier == .core }
	}

	public static let bundled = ManagedSupportCatalog(components: [
		Self.component("pyright", "Pyright", .languageServer, .core, ["python"], "pyright-langserver", ["--stdio"], .managed, "https://github.com/microsoft/pyright"),
		Self.component("typescript-language-server", "TypeScript Language Server", .languageServer, .core, ["javascript", "typescript"], "typescript-language-server", ["--stdio"], .managed, "https://github.com/typescript-language-server/typescript-language-server"),
		Self.component("omnisharp", "OmniSharp", .languageServer, .core, ["csharp"], "omnisharp", ["--languageserver"], .managed, "https://github.com/OmniSharp/omnisharp-roslyn", artifacts: .init(
			arm64: .init(version: "1.39.15", archiveURL: URL(string: "https://github.com/OmniSharp/omnisharp-roslyn/releases/download/v1.39.15/omnisharp-osx-arm64-net6.0.zip")!, sha256: "678be5bb972d04bbf5e1426e5e7562261e176fa781784d0f13877d8c4391ec3e", format: .zip, executablePaths: ["OmniSharp"]),
			x86_64: .init(version: "1.39.15", archiveURL: URL(string: "https://github.com/OmniSharp/omnisharp-roslyn/releases/download/v1.39.15/omnisharp-osx-x64-net6.0.zip")!, sha256: "6ac1f8b1dfb1e4515f61d120f2cb5ab8404134ec62c441e4ab70ef30e0ac6d07", format: .zip, executablePaths: ["OmniSharp"])
		)),
		Self.component("clangd", "clangd", .languageServer, .core, ["c", "cpp"], "clangd", [], .system, "https://clangd.llvm.org/installation.html", "Install Xcode Command Line Tools."),
		Self.component("debugpy", "debugpy", .debugAdapter, .core, ["python"], "python3", ["-m", "debugpy.adapter"], .managed, "https://github.com/microsoft/debugpy"),
		Self.component("vscode-js-debug", "VS Code JavaScript Debugger", .debugAdapter, .core, ["javascript", "typescript"], "js-debug-adapter", [], .managed, "https://github.com/microsoft/vscode-js-debug"),
		Self.component("lldb-dap", "LLDB-DAP", .debugAdapter, .core, ["c", "cpp"], "lldb-dap", [], .system, "https://lldb.llvm.org/use/lldbdap.html", "Install Xcode Command Line Tools."),
		Self.component("sourcekit-lsp", "SourceKit-LSP", .languageServer, .onDemand, ["swift"], "/usr/bin/xcrun", ["sourcekit-lsp"], .system, "https://developer.apple.com/documentation/xcode/xcode-command-line-tool-reference", "Install Xcode Command Line Tools."),
		Self.component("rust-analyzer", "rust-analyzer", .languageServer, .onDemand, ["rust"], "rust-analyzer", [], .managed, "https://github.com/rust-lang/rust-analyzer"),
		Self.component("gopls", "gopls", .languageServer, .onDemand, ["go"], "gopls", [], .managed, "https://pkg.go.dev/golang.org/x/tools/gopls"),
		Self.component("zls", "ZLS", .languageServer, .onDemand, ["zig"], "zls", [], .managed, "https://github.com/zigtools/zls"),
		Self.component("elixir-ls", "ElixirLS", .languageServer, .onDemand, ["elixir"], "elixir-ls", [], .managed, "https://github.com/elixir-lsp/elixir-ls"),
		Self.component("kotlin-language-server", "Kotlin Language Server", .languageServer, .onDemand, ["kotlin"], "kotlin-language-server", [], .managed, "https://github.com/fwcd/kotlin-language-server"),
		Self.component("bash-language-server", "Bash Language Server", .languageServer, .onDemand, ["bash"], "bash-language-server", ["start"], .managed, "https://github.com/bash-lsp/bash-language-server"),
		Self.component("docker-langserver", "Dockerfile Language Server", .languageServer, .onDemand, ["dockerfile"], "docker-langserver", ["--stdio"], .managed, "https://github.com/rcjsuen/dockerfile-language-server-nodejs"),
		Self.component("sqls", "sqls", .languageServer, .onDemand, ["sql"], "sqls", [], .managed, "https://github.com/sqls-server/sqls", artifacts: .init(
			arm64: .init(version: "0.2.48", archiveURL: URL(string: "https://github.com/sqls-server/sqls/releases/download/v0.2.48/sqls-darwin-0.2.48.zip")!, sha256: "b44165ca597a4b4298d56657bc911aa3ca8a591befefde4e29566923c6229f3d", format: .zip, executablePaths: ["sqls"]),
			x86_64: .init(version: "0.2.48", archiveURL: URL(string: "https://github.com/sqls-server/sqls/releases/download/v0.2.48/sqls-darwin-0.2.48.zip")!, sha256: "b44165ca597a4b4298d56657bc911aa3ca8a591befefde4e29566923c6229f3d", format: .zip, executablePaths: ["sqls"])
		)),
		Self.component("dart", "Dart", .languageServer, .onDemand, ["dart"], "dart", ["language-server", "--protocol=lsp"], .managed, "https://dart.dev/tools/sdk"),
		Self.component("haskell-language-server", "Haskell Language Server", .languageServer, .onDemand, ["haskell"], "haskell-language-server-wrapper", ["--lsp"], .managed, "https://github.com/haskell/haskell-language-server"),
		Self.component("lua-language-server", "Lua Language Server", .languageServer, .onDemand, ["lua"], "lua-language-server", [], .managed, "https://github.com/LuaLS/lua-language-server"),
		Self.component("ruby-lsp", "Ruby LSP", .languageServer, .onDemand, ["ruby"], "ruby-lsp", [], .managed, "https://github.com/Shopify/ruby-lsp"),
		Self.component("terraform-ls", "Terraform Language Server", .languageServer, .onDemand, ["terraform"], "terraform-ls", ["serve"], .managed, "https://github.com/hashicorp/terraform-ls"),
		Self.component("delve", "Delve", .debugAdapter, .onDemand, ["go"], "dlv", ["dap"], .managed, "https://github.com/go-delve/delve"),
		Self.component("codelldb", "CodeLLDB", .debugAdapter, .onDemand, ["rust"], "codelldb", [], .managed, "https://github.com/vadimcn/codelldb", artifacts: .init(
			arm64: .init(version: "1.12.2", archiveURL: URL(string: "https://github.com/vadimcn/codelldb/releases/download/v1.12.2/codelldb-darwin-arm64.vsix")!, sha256: "c836b81c6f2da467b5920a376a7bfc849dc4b4d81b19779dedf1c685cb4aa1a0", format: .zip, executablePaths: Self.codeLLDBExecutablePaths),
			x86_64: .init(version: "1.12.2", archiveURL: URL(string: "https://github.com/vadimcn/codelldb/releases/download/v1.12.2/codelldb-darwin-x64.vsix")!, sha256: "8270a342929bdc0deb6d7d3931c08d5ba6018265f840dd0508c4247fb8d32e8d", format: .zip, executablePaths: Self.codeLLDBExecutablePaths)
		)),
	])

	private static let codeLLDBExecutablePaths = [
		"extension/bin/codelldb-launch", "extension/adapter/codelldb", "extension/lldb/bin/lldb-server", "extension/lldb/bin/lldb-argdumper", "extension/lldb/bin/lldb", "extension/lldb/lib/libpython312.dylib", "extension/lldb/lib/liblldb.dylib", "extension/lldb/lib/python3.12/webbrowser.py", "extension/lldb/lib/python3.12/trace.py", "extension/lldb/lib/python3.12/timeit.py", "extension/lldb/lib/python3.12/tarfile.py", "extension/lldb/lib/python3.12/tabnanny.py", "extension/lldb/lib/python3.12/smtplib.py", "extension/lldb/lib/python3.12/quopri.py", "extension/lldb/lib/python3.12/pydoc.py", "extension/lldb/lib/python3.12/profile.py", "extension/lldb/lib/python3.12/platform.py", "extension/lldb/lib/python3.12/pdb.py", "extension/lldb/lib/python3.12/cgi.py", "extension/lldb/lib/python3.12/cProfile.py", "extension/lldb/lib/python3.12/base64.py", "extension/lldb/lib/lldb-python/lldb/lldb-argdumper", "extension/lldb/lib/python3.12/encodings/rot_13.py", "extension/lldb/lib/lldb-python/lldb/macosx/crashlog.py", "extension/lldb/lib/lldb-python/lldb/utils/symbolication.py", "extension/lldb/lib/lldb-python/lldb/utils/in_call_stack.py",
	]

	private static func component(
		_ id: String,
		_ displayName: String,
		_ kind: ManagedSupportKind,
		_ tier: ManagedSupportTier,
		_ languageIDs: [String],
		_ command: String,
		_ arguments: [String],
		_ installMode: ManagedSupportInstallMode,
		_ officialURL: String,
		_ systemInstallHint: String? = nil,
		artifacts: ManagedSupportArtifacts = .init()
	) -> ManagedSupportComponent {
		ManagedSupportComponent(
			id: id,
			displayName: displayName,
			kind: kind,
			tier: tier,
			languageIDs: languageIDs,
			command: command,
			arguments: arguments,
			installMode: installMode,
			officialURL: URL(string: officialURL)!,
			systemInstallHint: systemInstallHint,
			artifacts: artifacts
		)
	}
}
