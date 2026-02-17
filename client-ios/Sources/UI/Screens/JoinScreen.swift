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
                        VStack(alignment: .leading, spacing: 3) {
                            Text(call.roomId)
                                .font(.subheadline.monospaced())
                                .lineLimit(1)

                            Text(formatDate(call.startTime))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Spacer()

                        let count = roomStatuses[call.roomId] ?? 0
                        Text("\(count)")
                            .font(.caption.bold())
                            .frame(width: 22, height: 22)
                            .background(count > 0 ? Color.green.opacity(0.2) : Color.gray.opacity(0.2))
                            .clipShape(Circle())
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

    private func formatDate(_ timestampMs: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
