import SwiftUI

struct PairingView: View {
    @ObservedObject var service: GraftService

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let image = service.qrImage {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 180, height: 180)
            } else {
                ProgressView()
                    .frame(width: 180, height: 180)
            }

            if let code = service.fallbackCode {
                Text("Or enter this code: \(PairingViewState(fallbackCode: code).displayCode)")
                    .font(.callout)
                    .textSelection(.enabled)
            }

            Text("The code expires in two minutes.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

struct PairingViewState {
    let fallbackCode: String

    var displayCode: String {
        guard fallbackCode.count == 6 else { return fallbackCode }
        let index = fallbackCode.index(fallbackCode.startIndex, offsetBy: 3)
        return "\(fallbackCode[..<index]) \(fallbackCode[index...])"
    }
}
