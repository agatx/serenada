import SwiftUI

struct JoinScreen: View {
    let isBusy: Bool
    let statusMessage: String
    let recentCalls: [RecentCall]
    let roomStatuses: [String: Int]
    let onOpenJoinWithCode: () -> Void
    let onOpenSettings: () -> Void
    let onStartCall: () -> Void
    let onJoinRecentCall: (String) -> Void
    let onRemoveRecentCall: (String) -> Void

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                topBar
                ScrollView {
                    VStack(spacing: 20) {
                        Spacer().frame(height: 12)
                        Text(L10n.appName)
                            .font(.system(size: 42, weight: .bold))

                        Text(L10n.joinSubtitle)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)

                        if recentCalls.isEmpty {
                            Text(L10n.noRecentCalls)
                                .foregroundStyle(.secondary)
                                .padding(.top, 24)
                        } else {
                            RecentCallsSection(
                                calls: recentCalls,
                                roomStatuses: roomStatuses,
                                isBusy: isBusy,
                                onJoinRecentCall: onJoinRecentCall,
                                onRemoveRecentCall: onRemoveRecentCall
                            )
                            .padding(.top, 24)
                        }

                        Spacer(minLength: 120)
                    }
                    .padding(.horizontal, 20)
                }
            }

            Button(action: onStartCall) {
                HStack(spacing: 8) {
                    Image(systemName: "video.fill")
                    Text(L10n.joinStartCall)
                }
                .font(.system(size: 16, weight: .semibold))
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .background(isBusy ? Color.gray.opacity(0.5) : Color.accentColor)
                .foregroundStyle(.white)
                .clipShape(Capsule())
            }
            .disabled(isBusy)
            .padding(.trailing, 20)
            .padding(.bottom, 20)

            if isBusy {
                ZStack {
                    Color.black.opacity(0.2).ignoresSafeArea()
                    VStack(spacing: 12) {
                        ProgressView()
                        if !statusMessage.isEmpty {
                            Text(statusMessage)
                                .font(.callout)
                        }
                    }
                    .padding(24)
                    .background(.ultraThinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                }
            }
        }
    }

    private var topBar: some View {
        HStack(spacing: 12) {
            Button(action: onOpenJoinWithCode) {
                HStack {
                    Image(systemName: "magnifyingglass")
                    Text(L10n.joinEnterCodeOrLink)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(Color(.secondarySystemBackground))
                .clipShape(Capsule())
            }
            .buttonStyle(.plain)

            Button(action: onOpenSettings) {
                Image(systemName: "gearshape.fill")
                    .font(.title3)
            }
            .disabled(isBusy)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }
}

private struct RecentCallsSection: View {
    let calls: [RecentCall]
    let roomStatuses: [String: Int]
    let isBusy: Bool
    let onJoinRecentCall: (String) -> Void
    let onRemoveRecentCall: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L10n.recentCallsTitle)
                .font(.headline)
                .padding(.horizontal, 14)
                .padding(.top, 12)
                .padding(.bottom, 8)

            ForEach(calls) { call in
                Button {
                    onJoinRecentCall(call.roomId)
                } label: {
                    HStack(spacing: 12) {
                        StatusDot(count: roomStatuses[call.roomId] ?? 0)

                        HStack(spacing: 6) {
                            Image(systemName: "calendar")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(formatDateTime(call.startTime))
                                .font(.subheadline)
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                        }

                        Spacer()

                        HStack(spacing: 6) {
                            Image(systemName: "clock")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(formatDuration(call.durationSeconds))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
                .buttonStyle(.plain)
                .contextMenu {
                    Button(role: .destructive) {
                        onRemoveRecentCall(call.roomId)
                    } label: {
                        Label(L10n.recentCallsRemove, systemImage: "trash")
                    }
                }
                .disabled(isBusy)

                if call.id != calls.last?.id {
                    Divider().padding(.leading, 14)
                }
            }
        }
        .background(Color(.secondarySystemBackground).opacity(0.65))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private func formatDateTime(_ timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)

        let dateFormatter = DateFormatter()
        dateFormatter.locale = .autoupdatingCurrent
        dateFormatter.setLocalizedDateFormatFromTemplate("MMM d")

        let timeFormatter = DateFormatter()
        timeFormatter.locale = .autoupdatingCurrent
        timeFormatter.timeStyle = .short

        return "\(dateFormatter.string(from: date)) \(L10n.recentCallsAt) \(timeFormatter.string(from: date))"
    }

    private func formatDuration(_ durationSeconds: Int) -> String {
        let seconds = max(0, durationSeconds)
        if seconds < 60 { return "\(seconds)s" }
        let minutes = seconds / 60
        let remainderSeconds = seconds % 60
        return "\(minutes)m \(remainderSeconds)s"
    }
}

private struct StatusDot: View {
    let count: Int

    private var dotColor: Color {
        if count == 1 {
            return Color(red: 0.247, green: 0.725, blue: 0.314)
        }
        if count >= 2 {
            return Color(red: 0.824, green: 0.600, blue: 0.133)
        }
        return .clear
    }

    var body: some View {
        Circle()
            .fill(dotColor)
            .frame(width: 8, height: 8)
    }
}
