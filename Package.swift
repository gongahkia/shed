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
		.target(name: "PicoRender", dependencies: ["PicoEditor"]),
		.target(name: "PicoEditor"),
		.target(name: "PicoSyntax", dependencies: ["CTreeSitter", "CTSGrammars", "PicoEditor"]),
		.target(name: "PicoKeymap"),
		.executableTarget(name: "PicoBench"),
		.target(name: "CTreeSitter", sources: ["stub.c"]),
		.target(name: "CTSGrammars", sources: ["stub.c"]),
	]
)
