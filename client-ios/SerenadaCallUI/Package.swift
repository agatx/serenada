// swift-tools-version: 5.10

import PackageDescription

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
