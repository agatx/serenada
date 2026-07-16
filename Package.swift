// swift-tools-version: 5.9

import PackageDescription

// Single manifest for the repo's iOS packages. External consumers depend on
// it via this repo's Git URL (SwiftPM requires Package.swift at the repo
// root); the local SerenadaiOS app consumes the same package through
// client-ios/project.yml (path: ..).

let package = Package(
    name: "Serenada",
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
        ),
        .library(
            name: "SerenadaCallUI",
            targets: ["SerenadaCallUI"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/zelloptt/zello-ios-web-rtc", exact: "0.0.1")
    ],
    targets: [
        .target(
            name: "SerenadaBroadcastExtensionSupport",
            path: "client-ios/SerenadaCore/BroadcastSupport"
        ),
        .target(
            name: "SerenadaCore",
            dependencies: [
                .product(name: "WebRTC", package: "zello-ios-web-rtc"),
                "SerenadaBroadcastExtensionSupport"
            ],
            path: "client-ios/SerenadaCore/Sources"
        ),
        .target(
            name: "SerenadaCallUI",
            dependencies: [
                "SerenadaCore",
                .product(name: "WebRTC", package: "zello-ios-web-rtc")
            ],
            path: "client-ios/SerenadaCallUI/Sources"
        ),
        .testTarget(
            name: "SerenadaCoreTests",
            dependencies: ["SerenadaCore"],
            path: "client-ios/SerenadaCore/Tests/SerenadaCoreTests"
        ),
        .testTarget(
            name: "SerenadaCallUITests",
            dependencies: [
                "SerenadaCallUI",
                "SerenadaCore"
            ],
            path: "client-ios/SerenadaCallUI/Tests/SerenadaCallUITests"
        )
    ]
)
