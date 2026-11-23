import Foundation
import GraftMacKit

/// One live transfer row, with the protocol id so two files of the same name stay distinct.
struct TransferRow: Equatable {
    let id: UInt32
    let progress: TransferProgress
}

/// Live throughput and remaining-time estimate for an active transfer.
struct TransferMetrics: Equatable {
    let bytesPerSecond: Double
    let remainingSeconds: TimeInterval?

    /// Compact label for the popover, e.g. `"14.2 MiB/s · 8s left"`.
    var label: String {
        let speed = Self.formatSpeed(bytesPerSecond)
        if let eta = remainingSeconds {
            return "\(speed) · \(Self.formatETA(eta))"
        }
        return speed
    }

    static func formatSpeed(_ bytesPerSecond: Double) -> String {
        let kib = bytesPerSecond / 1024
        if kib < 1024 {
            return String(format: "%.0f KiB/s", max(kib, 1))
        }
        return String(format: "%.1f MiB/s", kib / 1024)
    }

    static func formatETA(_ seconds: TimeInterval) -> String {
        let rounded = Int(seconds.rounded(.up))
        if rounded <= 1 { return "< 1s left" }
        if rounded < 60 { return "\(rounded)s left" }
        let minutes = rounded / 60
        let remSeconds = rounded % 60
        if minutes < 60 {
            return remSeconds == 0 ? "\(minutes)m left" : "\(minutes)m \(remSeconds)s left"
        }
        let hours = minutes / 60
        let remMinutes = minutes % 60
        return "\(hours)h \(remMinutes)m left"
    }
}

/// Computes smoothed transfer speed and ETA from progress snapshots.
final class TransferSpeedTracker {
    private struct Sample {
        let startTime: Date
        let startBytes: UInt64
    }

    private var samples: [UInt32: Sample] = [:]

    func update(
        transfers: [UInt32: TransferProgress],
        now: Date = Date()
    ) -> [UInt32: TransferMetrics] {
        // Drop entries for finished or cancelled transfers.
        samples = samples.filter { transfers.keys.contains($0.key) }

        var metrics: [UInt32: TransferMetrics] = [:]
        for (id, progress) in transfers {
            guard let sample = samples[id] else {
                samples[id] = Sample(startTime: now, startBytes: progress.confirmed)
                continue
            }
            let elapsed = now.timeIntervalSince(sample.startTime)
            guard elapsed >= 0.5, progress.confirmed > sample.startBytes else { continue }
            let moved = Double(progress.confirmed - sample.startBytes)
            let rate = moved / elapsed
            guard rate > 0 else { continue }
            let remaining: TimeInterval? = progress.total > progress.confirmed
                ? Double(progress.total - progress.confirmed) / rate
                : nil
            metrics[id] = TransferMetrics(bytesPerSecond: rate, remainingSeconds: remaining)
        }
        return metrics
    }

    func reset() {
        samples.removeAll()
    }
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
