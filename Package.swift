// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "olly",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "ollyApp", targets: ["ollyApp"]),
        .executable(name: "ollyctl", targets: ["ollyctl"]),
        .executable(name: "PerfBench", targets: ["PerfBench"]),
        .executable(name: "SoakHarness", targets: ["SoakHarness"]),
        .library(name: "ollyKit", targets: ["ollyKit"]),
        .library(name: "ollyCore", targets: ["ollyCore"]),
        .library(name: "ollyLayouts", targets: ["ollyLayouts"]),
        .library(name: "ollyDSL", targets: ["ollyDSL"]),
        .library(name: "ollyIPC", targets: ["ollyIPC"]),
        .library(name: "ollyRuntime", targets: ["ollyRuntime"])
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-argument-parser", from: "1.8.2")
    ],
    targets: [
        .target(name: "ollyKit", exclude: ["README.md"]),
        .target(name: "ollyCore", dependencies: ["ollyKit"], exclude: ["README.md"]),
        .target(name: "ollyLayouts", dependencies: ["ollyCore", "ollyKit"], exclude: ["README.md"]),
        .target(name: "ollyDSL", dependencies: ["ollyCore", "ollyKit", "ollyLayouts"], exclude: ["README.md"]),
        .target(name: "ollyIPC", dependencies: ["ollyKit", "ollyCore", "ollyLayouts"], exclude: ["README.md"]),
        .target(name: "ollyRuntime", dependencies: ["ollyKit", "ollyCore", "ollyLayouts", "ollyDSL", "ollyIPC"]),
        .executableTarget(
            name: "ollyApp",
            dependencies: ["ollyKit", "ollyCore", "ollyLayouts", "ollyDSL", "ollyIPC", "ollyRuntime"],
            exclude: ["README.md"]
        ),
        .executableTarget(
            name: "ollyctl",
            dependencies: [
                "ollyDSL",
                "ollyIPC",
                "ollyLayouts",
                .product(name: "ArgumentParser", package: "swift-argument-parser")
            ],
            exclude: ["README.md"]
        ),
        .executableTarget(
            name: "PerfBench",
            dependencies: ["ollyKit", "ollyCore", "ollyLayouts", "ollyDSL"]
        ),
        .executableTarget(
            name: "SoakHarness",
            dependencies: ["ollyKit"]
        ),
        .testTarget(name: "ollyKitTests", dependencies: ["ollyKit"]),
        .testTarget(name: "ollyCoreTests", dependencies: ["ollyCore"]),
        .testTarget(name: "ollyLayoutsTests", dependencies: ["ollyLayouts"], exclude: ["Fixtures"]),
        .testTarget(name: "ollyDSLTests", dependencies: ["ollyDSL"]),
        .testTarget(name: "ollyIPCTests", dependencies: ["ollyIPC"]),
        .testTarget(
            name: "ollyRuntimeTests",
            dependencies: ["ollyRuntime", "ollyDSL", "ollyIPC", "ollyKit", "ollyLayouts"]
        )
    ]
)
