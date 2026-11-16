import Foundation
import GraftMacKit

/// One live transfer row, with the protocol id so two files of the same name stay distinct.
struct TransferRow: Equatable {
    let id: UInt32
    let progress: TransferProgress
}

/// Which transfer rows the popover shows.
struct TransferSummary: Equatable {
    let rows: [TransferRow]
    let failure: String?

    static func from(
        transfers: [UInt32: TransferProgress],
        finished: Set<UInt32>,
        failure: String?
    ) -> TransferSummary {
        TransferSummary(
            rows: transfers
                .filter { !finished.contains($0.key) }
                .sorted { $0.key < $1.key }
                .map { TransferRow(id: $0.key, progress: $0.value) },
            failure: failure
        )
    }
}
