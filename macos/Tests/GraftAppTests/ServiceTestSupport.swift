import XCTest

/// A socket bound to a free port, so a test can learn a port and release it for the service.
struct BoundPort {
    let socket: Int32
    let number: UInt16
}

func temporaryStoreURL() -> URL {
    URL(fileURLWithPath: NSTemporaryDirectory())
        .appendingPathComponent("graft-service-\(UUID().uuidString)", isDirectory: true)
        .appendingPathComponent("peers.json")
}

func occupyFreePort() throws -> BoundPort {
    let socketFD = socket(AF_INET, SOCK_STREAM, 0)
    XCTAssertGreaterThanOrEqual(socketFD, 0)

    var address = sockaddr_in()
    address.sin_family = sa_family_t(AF_INET)
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_addr.s_addr = inet_addr("127.0.0.1")
    address.sin_port = 0

    let bound = withUnsafePointer(to: &address) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.bind(socketFD, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard bound == 0 else { throw XCTSkip("could not bind a probe socket") }

    var length = socklen_t(MemoryLayout<sockaddr_in>.size)
    withUnsafeMutablePointer(to: &address) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.getsockname(socketFD, $0, &length)
        }
    }
    return BoundPort(socket: socketFD, number: UInt16(bigEndian: address.sin_port))
}

func closeProbeSocket(_ socketFD: Int32) {
    Darwin.close(socketFD)
}

/// A fresh identity tag per test, removed afterwards.
///
/// The Keychain authorizes an item for the binary that created it, so the app and the test bundle
/// cannot share `kr.jjw.graft.mac`; a shared tag makes the suite depend on which binary ran last.
func temporaryIdentityTag() -> String {
    "kr.jjw.graft.test.\(UUID().uuidString)"
}
