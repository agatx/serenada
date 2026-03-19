// swift-tools-version: 5.10

import PackageDescription

// When consuming SerenadaCore from a remote Git URL, override the WebRTC
// binary target path below with the appropriate remote URL or local checkout.
// For local development inside the Serenada monorepo the relative path works.

let package = Package(
    name: "SerenadaCore",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "SerenadaCore",
            targets: ["SerenadaCore"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "WebRTC",
            path: "../Vendor/WebRTC/WebRTC.xcframework"
        ),
        .target(
            name: "SerenadaCore",
            dependencies: ["WebRTC"],
            path: "Sources",
            swiftSettings: [
                .define("BROADCAST_EXTENSION")
            ]
        ),
        .testTarget(
            name: "SerenadaCoreTests",
            dependencies: ["SerenadaCore"],
            path: "Tests/SerenadaCoreTests"
        )
    ]
)
