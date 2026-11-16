import Foundation

/// What the popover says at a glance: the state, and the one line that explains it.
struct PopoverSummary: Equatable {
    let title: String
    let detail: String?
    let showsError: Bool

    static func from(
        state: MenuState,
        peerAddress: String?,
        fingerprintPrefix: String?
    ) -> PopoverSummary {
        switch state.status {
        case .connected:
            return PopoverSummary(
                title: state.statusLabel,
                detail: joined([peerAddress, fingerprintPrefix]),
                showsError: false
            )

        case .pairing:
            return PopoverSummary(
                title: state.statusLabel,
                detail: joined([fingerprintPrefix, peerAddress]),
                showsError: false
            )

        case .searching:
            return PopoverSummary(
                title: state.statusLabel,
                detail: peerAddress,
                showsError: false
            )

        case .failed(let message):
            return PopoverSummary(title: message, detail: nil, showsError: true)

        case .idle:
            return PopoverSummary(
                title: "Not connected",
                detail: "Open Graft on your phone",
                showsError: false
            )
        }
    }

    private static func joined(_ parts: [String?]) -> String? {
        let present = parts.compactMap { $0 }.filter { !$0.isEmpty }
        return present.isEmpty ? nil : present.joined(separator: " · ")
    }
}
