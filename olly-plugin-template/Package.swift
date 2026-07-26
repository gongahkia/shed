// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "olly-plugin-template",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(name: "HelloOllyLayout", targets: ["HelloOllyLayout"])
    ],
    dependencies: [
        .package(path: "..")
    ],
    targets: [
        .target(
            name: "HelloOllyLayout",
            dependencies: [
                .product(name: "ollyKit", package: "olly"),
                .product(name: "ollyLayouts", package: "olly")
            ]
        ),
        .testTarget(
            name: "HelloOllyLayoutTests",
            dependencies: ["HelloOllyLayout"]
        )
    ]
)
