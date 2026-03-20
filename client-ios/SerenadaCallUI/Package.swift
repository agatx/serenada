// swift-tools-version: 5.10

import PackageDescription

// For external distribution, replace the path-based SerenadaCore dependency
// with the location where you publish the SerenadaCore package. There is no
// standalone public SerenadaCore package repo configured in this monorepo yet.

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
        ),
        .testTarget(
            name: "SerenadaCallUITests",
            dependencies: [
                "SerenadaCallUI",
                .product(name: "SerenadaCore", package: "SerenadaCore")
            ],
            path: "Tests/SerenadaCallUITests"
        )
    ]
)
