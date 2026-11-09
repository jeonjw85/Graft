// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Graft",
    platforms: [.macOS(.v13)],
    products: [.library(name: "GraftProtocol", targets: ["GraftProtocol"])],
    targets: [
        .target(name: "GraftProtocol"),
        .testTarget(name: "GraftProtocolTests", dependencies: ["GraftProtocol"])
    ]
)
