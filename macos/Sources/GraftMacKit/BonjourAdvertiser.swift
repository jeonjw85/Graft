import Foundation
import Network

/// Advertises the Mac on the LAN as `_graft._tcp` with the fingerprint in the TXT record.
///
/// The service instance name carries only eight characters of the fingerprint for uniqueness; the
/// TXT record carries the full value so a phone can verify it before connecting.
public final class BonjourAdvertiser {
    public enum Error: Swift.Error {
        case unsupportedName
    }

    private let port: UInt16
    private let fingerprint: String
    private let deviceName: String
    private var listener: NWListener?

    public init(port: UInt16, fingerprint: String, deviceName: String) {
        self.port = port
        self.fingerprint = fingerprint
        self.deviceName = deviceName
    }

    public func start() throws {
        guard port != 0, !fingerprint.isEmpty else { throw Error.unsupportedName }
        stop()

        let parameters = NWParameters.tcp
        let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: port)!)
        listener.service = NWListener.Service(
            name: Self.serviceName(deviceName: deviceName, fingerprint: fingerprint),
            type: BonjourServiceInfo.serviceType,
            txtRecord: Self.txtRecord(fingerprint: fingerprint)
        )
        listener.start(queue: .main)
        self.listener = listener
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    static func serviceName(deviceName: String, fingerprint: String) -> String {
        BonjourServiceInfo.serviceName(deviceName: deviceName, fingerprint: fingerprint)
    }

    static func txtRecord(fingerprint: String) -> Data {
        var data = Data()
        for (key, value) in [("fp", fingerprint), ("v", "1")] {
            let entry = "\(key)=\(value)"
            guard entry.utf8.count < 255 else { continue }
            data.append(UInt8(entry.utf8.count))
            data.append(contentsOf: Array(entry.utf8))
        }
        return data
    }
}

/// Shared naming rules with the Kotlin implementation so both sides agree byte for byte.
public enum BonjourServiceInfo {
    public static let serviceType = "_graft._tcp"
    public static let maxNameBytes = 63

    public static func serviceName(deviceName: String, fingerprint: String) -> String {
        truncate("\(deviceName) (\(fingerprint.prefix(8)))", maxBytes: maxNameBytes)
    }

    public static func shortFingerprint(from serviceName: String) -> String? {
        guard let open = serviceName.lastIndex(of: "("),
              let close = serviceName.lastIndex(of: ")"),
              close > serviceName.index(after: open) else {
            return nil
        }
        let value = String(serviceName[serviceName.index(after: open)..<close])
        return value.count == 8 ? value : nil
    }

    /// Truncates on a UTF-8 boundary so a Korean device name is never cut mid-character.
    public static func truncate(_ value: String, maxBytes: Int) -> String {
        guard value.utf8.count > maxBytes else { return value }
        var result = ""
        var used = 0
        for character in value {
            let size = String(character).utf8.count
            if used + size > maxBytes { break }
            result.append(character)
            used += size
        }
        return result
    }
}
