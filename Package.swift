// swift-tools-version:5.9

import PackageDescription

let package = Package(
	name: "Pico",
	platforms: [
		.macOS(.v13),
	],
	products: [
		.executable(name: "PicoApp", targets: ["PicoApp"]),
		.executable(name: "PicoBench", targets: ["PicoBench"]),
		.library(name: "PicoRender", targets: ["PicoRender"]),
		.library(name: "PicoEditor", targets: ["PicoEditor"]),
		.library(name: "PicoSyntax", targets: ["PicoSyntax"]),
		.library(name: "PicoKeymap", targets: ["PicoKeymap"]),
		.library(name: "CTreeSitter", targets: ["CTreeSitter"]),
		.library(name: "CTSGrammars", targets: ["CTSGrammars"]),
	],
	dependencies: [],
	targets: [
		.executableTarget(
			name: "PicoApp",
			dependencies: ["PicoRender", "PicoEditor", "PicoSyntax", "PicoKeymap"]
		),
		.target(name: "PicoRender", dependencies: ["PicoEditor", "PicoKeymap"], resources: [.copy("Shaders.metal")]),
		.target(name: "PicoEditor"),
		.target(name: "PicoSyntax", dependencies: ["CTreeSitter", "CTSGrammars", "PicoEditor"], resources: [.copy("Resources")]),
		.target(name: "PicoKeymap", resources: [.process("Resources")]),
		.executableTarget(name: "PicoBench", dependencies: ["PicoEditor"]),
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
		.testTarget(name: "PicoEditorTests", dependencies: ["PicoEditor"]),
		.testTarget(name: "PicoKeymapTests", dependencies: ["PicoKeymap"]),
		.testTarget(name: "PicoRenderTests", dependencies: ["PicoRender"]),
		.testTarget(name: "PicoSyntaxTests", dependencies: ["PicoSyntax", "PicoEditor"]),
		.testTarget(name: "CTSGrammarsTests", dependencies: ["CTSGrammars"]),
	]
)
