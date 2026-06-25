// swift-tools-version: 5.9

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
    targets: [
        .binaryTarget(
            name: "WebRTC",
            path: "../Vendor/WebRTC/WebRTC.xcframework"
        ),
        .target(
            name: "SerenadaBroadcastExtensionSupport",
            path: "BroadcastSupport"
        ),
        .target(
            name: "SerenadaCore",
            dependencies: ["WebRTC", "SerenadaBroadcastExtensionSupport"],
            path: "Sources"
        ),
        .testTarget(
            name: "SerenadaCoreTests",
            dependencies: ["SerenadaCore"],
            path: "Tests/SerenadaCoreTests"
        )
    ]
)
