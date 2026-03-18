// swift-tools-version: 5.10

import PackageDescription

// For external distribution, replace the path-based SerenadaCore dependency
// with a remote Git URL:
//   .package(url: "https://github.com/AleGavrilov/serenada-ios-core.git", from: "0.1.0")

let package = Package(
    name: "SerenadaCallUI",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "SerenadaCallUI",
            targets: ["SerenadaCallUI"]
        )
    ],
    dependencies: [
        .package(path: "../SerenadaCore")
    ],
    targets: [
        .target(
            name: "SerenadaCallUI",
            dependencies: [
                .product(name: "SerenadaCore", package: "SerenadaCore")
            ],
            path: "Sources"
        )
    ]
)
