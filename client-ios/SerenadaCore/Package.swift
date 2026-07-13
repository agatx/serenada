// swift-tools-version: 5.9

import PackageDescription

// WebRTC comes from the zello-ios-web-rtc SPM package, which ships the
// prebuilt XCFramework as a binary target. Pinned to an exact version for
// reproducible builds; bump deliberately and re-verify call resilience.

let package = Package(
    name: "SerenadaCore",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "SerenadaCore",
            targets: ["SerenadaCore"]
        ),
        // Extension-safe surface for a broadcast upload extension: the IPC
        // config derivation, the shared-memory layout, the frame writer, and the
        // open `SerenadaBroadcastSampleHandler` base class. Pulls in no WebRTC or
        // SerenadaCore app APIs, so an app-extension target can depend on it.
        .library(
            name: "SerenadaBroadcastExtensionSupport",
            targets: ["SerenadaBroadcastExtensionSupport"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/zelloptt/zello-ios-web-rtc", exact: "0.0.1")
    ],
    targets: [
        .target(
            name: "SerenadaBroadcastExtensionSupport",
            path: "BroadcastSupport"
        ),
        .target(
            name: "SerenadaCore",
            dependencies: [
                .product(name: "WebRTC", package: "zello-ios-web-rtc"),
                "SerenadaBroadcastExtensionSupport"
            ],
            path: "Sources"
        ),
        .testTarget(
            name: "SerenadaCoreTests",
            dependencies: ["SerenadaCore"],
            path: "Tests/SerenadaCoreTests"
        )
    ]
)
