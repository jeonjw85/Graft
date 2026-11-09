// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Graft",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "GraftProtocol", targets: ["GraftProtocol"]),
        .library(name: "GraftMacKit", targets: ["GraftMacKit"]),
        .executable(name: "graft-headless", targets: ["graft-headless"])
    ],
    targets: [
        .target(name: "GraftProtocol"),
        .target(name: "GraftMacKit", dependencies: ["GraftProtocol"]),
        .executableTarget(name: "graft-headless", dependencies: ["GraftMacKit", "GraftProtocol"]),
        .testTarget(name: "GraftProtocolTests", dependencies: ["GraftProtocol"]),
        .testTarget(name: "GraftMacKitTests", dependencies: ["GraftMacKit", "GraftProtocol"])
    ]
)
