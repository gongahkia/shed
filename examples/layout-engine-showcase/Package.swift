// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "olly-layout-engine-showcase",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(name: "ShowcaseLayouts", targets: ["ShowcaseLayouts"])
    ],
    dependencies: [
        .package(path: "../..")
    ],
    targets: [
        .target(
            name: "ShowcaseLayouts",
            dependencies: [
                .product(name: "ollyCore", package: "olly"),
                .product(name: "ollyKit", package: "olly"),
                .product(name: "ollyLayouts", package: "olly")
            ]
        ),
        .testTarget(
            name: "ShowcaseLayoutsTests",
            dependencies: [
                "ShowcaseLayouts",
                .product(name: "ollyLayouts", package: "olly")
            ]
        )
    ]
)
