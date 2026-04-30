import SwiftUI

/// Three small green bars that pulse with the speaker's audio level — used
/// in place of the muted mic icon when a participant is unmuted.
///
/// Mirrors the web SDK's `AudioActivityIndicator` and Android's
/// `AudioActivityIndicator.kt` 1:1 — same bar gains, 14% minimum height,
/// animated transitions paced to the 100 ms update cadence of
/// `AudioLevelMonitor`.
struct AudioActivityIndicator: View {
    let level: Float
    var size: CGFloat = 14

    private static let barGains: [Float] = [0.7, 1.0, 0.55]
    private static let minHeightFraction: Float = 0.14
    private static let maxHeightFraction: Float = 1.0
    private static let barWidth: CGFloat = 3
    private static let barColor = Color(red: 0x22 / 255, green: 0xC5 / 255, blue: 0x5E / 255)
    private static let animationDuration: Double = 0.1

    var body: some View {
        let clamped = max(0, min(1, level))
        HStack(alignment: .center, spacing: 2) {
            ForEach(0..<Self.barGains.count, id: \.self) { index in
                let gain = Self.barGains[index]
                let target = Self.minHeightFraction +
                    (Self.maxHeightFraction - Self.minHeightFraction) * min(1, clamped * gain)
                Capsule()
                    .fill(Self.barColor)
                    .frame(width: Self.barWidth, height: size * CGFloat(target))
                    .animation(.easeOut(duration: Self.animationDuration), value: clamped)
            }
        }
        .frame(width: size, height: size)
    }
}
