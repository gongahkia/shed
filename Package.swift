// swift-tools-version:5.9

import PackageDescription

let releaseSwiftSettings: [SwiftSetting] = [
	.unsafeFlags([
		"-Xfrontend", "-disable-reflection-metadata",
		"-Xfrontend", "-disable-concrete-type-metadata-mangled-name-accessors",
	], .when(configuration: .release)),
]

let package = Package(
	name: "Itsy",
	platforms: [
		.macOS(.v13),
	],
	products: [
		.executable(name: "ItsyApp", targets: ["ItsyApp"]),
		.executable(name: "ItsyBench", targets: ["ItsyBench"]),
		.library(name: "ItsyRender", type: .static, targets: ["ItsyRender"]),
		.library(name: "ItsyEditor", type: .static, targets: ["ItsyEditor"]),
		.library(name: "ItsySyntax", type: .static, targets: ["ItsySyntax"]),
		.library(name: "ItsyKeymap", type: .static, targets: ["ItsyKeymap"]),
		.library(name: "ItsyLSP", type: .static, targets: ["ItsyLSP"]),
		.library(name: "ItsyDAP", type: .static, targets: ["ItsyDAP"]),
		.library(name: "CTreeSitter", type: .static, targets: ["CTreeSitter"]),
		.library(name: "CTSGrammars", type: .static, targets: ["CTSGrammars"]),
	],
	dependencies: [],
	targets: [
		.executableTarget(
			name: "ItsyApp",
			dependencies: ["ItsyRender", "ItsyEditor", "ItsySyntax", "ItsyKeymap"],
			swiftSettings: releaseSwiftSettings
		),
		.target(name: "ItsyRender", dependencies: ["ItsyEditor", "ItsyKeymap"], resources: [.copy("Shaders.metal")], swiftSettings: releaseSwiftSettings),
		.target(name: "ItsyEditor", dependencies: ["ItsyLSP"], swiftSettings: releaseSwiftSettings),
		.target(name: "ItsySyntax", dependencies: ["CTreeSitter", "ItsyEditor"], resources: [.copy("Resources")], swiftSettings: releaseSwiftSettings),
		.target(name: "ItsyKeymap", resources: [.process("Resources")], swiftSettings: releaseSwiftSettings),
		.target(name: "ItsyLSP", swiftSettings: releaseSwiftSettings),
		.target(name: "ItsyDAP", swiftSettings: releaseSwiftSettings),
		.executableTarget(name: "ItsyBench", dependencies: ["ItsyEditor"], swiftSettings: releaseSwiftSettings),
		.target(
			name: "CTreeSitter",
			path: "Sources/CTreeSitter",
			sources: ["upstream/lib/src/lib.c"],
			publicHeadersPath: "upstream/lib/include",
			cSettings: [
				.headerSearchPath("upstream/lib/src"),
				.unsafeFlags(["-O3"], .when(configuration: .release)),
			]
		),
		.target(
			name: "CTSGrammars",
			path: "Sources/CTSGrammars",
			sources: [
				"grammars/c/src/parser.c",
				"grammars/cpp/src/parser.c",
				"grammars/cpp/src/scanner.c",
				"grammars/css/src/parser.c",
				"grammars/css/src/scanner.c",
				"grammars/go/src/parser.c",
				"grammars/html/src/parser.c",
				"grammars/html/src/scanner.c",
				"grammars/javascript/src/parser.c",
				"grammars/javascript/src/scanner.c",
				"grammars/json/src/parser.c",
				"grammars/markdown/tree-sitter-markdown/src/parser.c",
				"grammars/markdown/tree-sitter-markdown/src/scanner.c",
				"grammars/markdown/tree-sitter-markdown-inline/src/parser.c",
				"grammars/markdown/tree-sitter-markdown-inline/src/scanner.c",
				"grammars/python/src/parser.c",
				"grammars/python/src/scanner.c",
				"grammars/rust/src/parser.c",
				"grammars/rust/src/scanner.c",
				"grammars/toml/src/parser.c",
				"grammars/toml/src/scanner.c",
				"grammars/typescript/typescript/src/parser.c",
				"grammars/typescript/typescript/src/scanner.c",
				"grammars/typescript/tsx/src/parser.c",
				"grammars/typescript/tsx/src/scanner.c",
				"grammars/yaml/src/parser.c",
				"grammars/yaml/src/scanner.c",
			],
			publicHeadersPath: "include",
			cSettings: [
				.headerSearchPath("grammars/c/src"),
				.headerSearchPath("grammars/cpp/src"),
				.headerSearchPath("grammars/css/src"),
				.headerSearchPath("grammars/go/src"),
				.headerSearchPath("grammars/html/src"),
				.headerSearchPath("grammars/javascript/src"),
				.headerSearchPath("grammars/json/src"),
				.headerSearchPath("grammars/markdown/tree-sitter-markdown/src"),
				.headerSearchPath("grammars/markdown/tree-sitter-markdown-inline/src"),
				.headerSearchPath("grammars/python/src"),
				.headerSearchPath("grammars/rust/src"),
				.headerSearchPath("grammars/toml/src"),
				.headerSearchPath("grammars/typescript/typescript/src"),
				.headerSearchPath("grammars/typescript/tsx/src"),
				.headerSearchPath("grammars/yaml/src"),
				.unsafeFlags(["-O3"], .when(configuration: .release)),
			]
		),
		.testTarget(name: "ItsyEditorTests", dependencies: ["ItsyEditor", "ItsyLSP"]),
		.testTarget(name: "ItsyKeymapTests", dependencies: ["ItsyKeymap"]),
		.testTarget(name: "ItsyRenderTests", dependencies: ["ItsyRender"]),
		.testTarget(name: "ItsySyntaxTests", dependencies: ["ItsySyntax", "ItsyEditor", "CTSGrammars"]),
		.testTarget(name: "ItsyLSPTests", dependencies: ["ItsyLSP"]),
		.testTarget(name: "ItsyDAPTests", dependencies: ["ItsyDAP"]),
		.testTarget(name: "CTSGrammarsTests", dependencies: ["CTSGrammars"]),
	]
)
