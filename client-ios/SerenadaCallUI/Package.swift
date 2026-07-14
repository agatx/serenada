// swift-tools-version: 5.9

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
        .package(path: "../SerenadaCore"),
        // WebRTC version is pinned by SerenadaCore (exact:); the open range here
        // intersects with that pin, so SerenadaCore/Package.swift stays the single
        // bump site.
        .package(url: "https://github.com/zelloptt/zello-ios-web-rtc", from: "0.0.1")
    ],
    targets: [
        .target(
            name: "SerenadaCallUI",
            dependencies: [
                .product(name: "SerenadaCore", package: "SerenadaCore"),
                .product(name: "WebRTC", package: "zello-ios-web-rtc")
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
