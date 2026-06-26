// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "olly",
    platforms: [
        .macOS(.v14),
    ],
    products: [
        .executable(name: "ollyApp", targets: ["ollyApp"]),
        .executable(name: "ollyctl", targets: ["ollyctl"]),
        .library(name: "ollyKit", targets: ["ollyKit"]),
        .library(name: "ollyCore", targets: ["ollyCore"]),
        .library(name: "ollyLayouts", targets: ["ollyLayouts"]),
        .library(name: "ollyDSL", targets: ["ollyDSL"]),
        .library(name: "ollyIPC", targets: ["ollyIPC"]),
    ],
    targets: [
        .target(name: "ollyKit"),
        .target(name: "ollyCore", dependencies: ["ollyKit"]),
        .target(name: "ollyLayouts", dependencies: ["ollyCore"]),
        .target(name: "ollyDSL", dependencies: ["ollyCore", "ollyLayouts"]),
        .target(name: "ollyIPC", dependencies: ["ollyCore"]),
        .executableTarget(
            name: "ollyApp",
            dependencies: ["ollyKit", "ollyCore", "ollyLayouts", "ollyDSL", "ollyIPC"]
        ),
        .executableTarget(name: "ollyctl", dependencies: ["ollyIPC"]),
    ]
)
