// swift-tools-version: 5.10

import PackageDescription

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
            path: "Sources"
        )
    ]
)
