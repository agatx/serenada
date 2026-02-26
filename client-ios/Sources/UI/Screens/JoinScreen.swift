import SwiftUI

struct JoinScreen: View {
    let isBusy: Bool
    let statusMessage: String
    let recentCalls: [RecentCall]
    let savedRooms: [SavedRoom]
    let areSavedRoomsShownFirst: Bool
    let roomStatuses: [String: Int]
    let onOpenJoinWithCode: () -> Void
    let onOpenSettings: () -> Void
    let onStartCall: () -> Void
    let onJoinRecentCall: (String) -> Void
    let onJoinSavedRoom: (SavedRoom) -> Void
    let onRemoveRecentCall: (String) -> Void
    let onSaveRoom: (String, String) -> Void
    let onCreateSavedRoomInviteLink: (String) async -> Result<String, Error>
    let onRemoveSavedRoom: (String) -> Void

    @State private var saveRoomDialogTargetId: String?
    @State private var saveRoomDialogName = ""
    @State private var saveRoomDialogIsRename = false

    @State private var showCreateRoomSheet = false
    @State private var shareLink: String?

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0) {
                topBar
                ScrollView {
                    VStack(spacing: 20) {
                        Spacer().frame(height: 12)
                        Text(L10n.appName)
                            .font(.largeTitle.bold())
                            .dynamicTypeSize(.xSmall ... .accessibility3)

                        Text(L10n.joinSubtitle)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)

                        contentSections

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
                busyOverlay
            }
        }
        .alert(
            saveRoomDialogIsRename ? L10n.savedRoomsDialogTitleRename : L10n.savedRoomsDialogTitleNew,
            isPresented: Binding(
                get: { saveRoomDialogTargetId != nil },
                set: { isPresented in
                    if !isPresented {
                        saveRoomDialogTargetId = nil
                        saveRoomDialogName = ""
                        saveRoomDialogIsRename = false
                    }
                }
            )
        ) {
            TextField(L10n.savedRoomsNameLabel, text: $saveRoomDialogName)
            Button(L10n.settingsCancel, role: .cancel) {}
            Button(L10n.settingsSave) {
                guard let roomId = saveRoomDialogTargetId else { return }
                onSaveRoom(roomId, saveRoomDialogName)
                saveRoomDialogTargetId = nil
                saveRoomDialogName = ""
                saveRoomDialogIsRename = false
            }
            .disabled(saveRoomDialogName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .sheet(isPresented: $showCreateRoomSheet) {
            CreateSavedRoomSheet(
                onCreateSavedRoomInviteLink: onCreateSavedRoomInviteLink,
                onCreatedLink: { link in
                    shareLink = link
                }
            )
        }
        .sheet(isPresented: Binding(
            get: { shareLink != nil },
            set: { if !$0 { shareLink = nil } }
        )) {
            if let shareLink {
                ActivityView(items: [shareLink])
            }
        }
        .overlay(alignment: .topLeading) {
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityIdentifier("join.screen")
        }
    }

    private var contentSections: some View {
        let hasSavedRooms = !savedRooms.isEmpty
        let hasRecentCalls = !recentCalls.isEmpty

        return VStack(spacing: 16) {
            if !hasSavedRooms && !hasRecentCalls {
                VStack(spacing: 12) {
                    Image(systemName: "phone.arrow.up.right")
                        .font(.system(size: 36, weight: .light))
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                    Text(L10n.noRecentCalls)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 24)
            } else if areSavedRoomsShownFirst {
                savedRoomsSection
                recentCallsSection
            } else {
                recentCallsSection
                savedRoomsSection
            }
        }
        .padding(.top, 24)
    }

    private var savedRoomsSection: some View {
        SavedRoomsSection(
            rooms: savedRooms,
            roomStatuses: roomStatuses,
            isBusy: isBusy,
            onCreate: { showCreateRoomSheet = true },
            onJoinSavedRoom: onJoinSavedRoom,
            onRenameSavedRoom: { room in
                saveRoomDialogTargetId = room.roomId
                saveRoomDialogName = room.name
                saveRoomDialogIsRename = true
            },
            onRemoveSavedRoom: onRemoveSavedRoom
        )
    }

    private var recentCallsSection: some View {
        RecentCallsSection(
            calls: recentCalls,
            roomStatuses: roomStatuses,
            savedRoomNameById: Dictionary(uniqueKeysWithValues: savedRooms.map { ($0.roomId, $0.name) }),
            isBusy: isBusy,
            onJoinRecentCall: onJoinRecentCall,
            onSaveRecentCall: { roomId, existingName in
                saveRoomDialogTargetId = roomId
                saveRoomDialogName = existingName ?? ""
                saveRoomDialogIsRename = existingName != nil
            },
            onRemoveRecentCall: onRemoveRecentCall
        )
    }

    private var busyOverlay: some View {
        ZStack {
            Color.black.opacity(0.2).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                if !statusMessage.isEmpty {
                    Text(statusMessage)
                        .font(.callout)
                        .accessibilityIdentifier("join.busyStatus")
                }
            }
            .padding(24)
            .background(.ultraThinMaterial)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .accessibilityIdentifier("join.busyOverlay")
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
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
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
    let savedRoomNameById: [String: String]
    let isBusy: Bool
    let onJoinRecentCall: (String) -> Void
    let onSaveRecentCall: (String, String?) -> Void
    let onRemoveRecentCall: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L10n.recentCallsTitle)
                .font(.headline)
                .padding(.horizontal, 4)
                .padding(.top, 8)
                .padding(.bottom, 4)

            VStack(alignment: .leading, spacing: 0) {
                ForEach(calls) { call in
                    let roomName = savedRoomNameById[call.roomId]
                    Button {
                        onJoinRecentCall(call.roomId)
                    } label: {
                        HStack(spacing: 12) {
                            StatusDot(count: roomStatuses[call.roomId] ?? 0)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(formatDateTime(call.startTime))
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                if let roomName {
                                    Text(roomName)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }

                            Spacer()

                            Text(formatDuration(call.durationSeconds))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("join.recentCall.\(call.roomId)")
                    .contextMenu {
                        let existingName = savedRoomNameById[call.roomId]
                        Button {
                            onSaveRecentCall(call.roomId, existingName)
                        } label: {
                            Label(existingName == nil ? L10n.savedRoomsSave : L10n.savedRoomsRename, systemImage: "square.and.pencil")
                        }

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

private struct SavedRoomsSection: View {
    let rooms: [SavedRoom]
    let roomStatuses: [String: Int]
    let isBusy: Bool
    let onCreate: () -> Void
    let onJoinSavedRoom: (SavedRoom) -> Void
    let onRenameSavedRoom: (SavedRoom) -> Void
    let onRemoveSavedRoom: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(rooms.isEmpty ? L10n.savedRoomsTitleEmpty : L10n.savedRoomsTitle)
                    .font(.headline)
                Spacer()
                Button(L10n.savedRoomsCreate, action: onCreate)
                    .disabled(isBusy)
            }
            .padding(.horizontal, 4)
            .padding(.top, 8)
            .padding(.bottom, 4)

            VStack(alignment: .leading, spacing: 0) {
                ForEach(rooms) { room in
                    Button {
                        onJoinSavedRoom(room)
                    } label: {
                        HStack(spacing: 12) {
                            StatusDot(count: roomStatuses[room.roomId] ?? 0)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(room.name)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Text(formatLastJoined(room.lastJoinedAt))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            onRenameSavedRoom(room)
                        } label: {
                            Label(L10n.savedRoomsRename, systemImage: "square.and.pencil")
                        }
                        Button(role: .destructive) {
                            onRemoveSavedRoom(room.roomId)
                        } label: {
                            Label(L10n.savedRoomsRemove, systemImage: "trash")
                        }
                    }
                    .disabled(isBusy)

                    if room.id != rooms.last?.id {
                        Divider().padding(.leading, 14)
                    }
                }
            }
            .background(Color(.secondarySystemBackground).opacity(0.65))
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }

    private func formatLastJoined(_ timestampMs: Int64?) -> String {
        guard let timestampMs, timestampMs > 0 else {
            return L10n.savedRoomsNeverJoined
        }
        let date = Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000)
        let formatter = DateFormatter()
        formatter.locale = .autoupdatingCurrent
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return String(format: L10n.savedRoomsLastJoined, formatter.string(from: date))
    }
}

private struct CreateSavedRoomSheet: View {
    let onCreateSavedRoomInviteLink: (String) async -> Result<String, Error>
    let onCreatedLink: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var roomName = ""
    @State private var isCreating = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.savedRoomsDialogTitleCreate) {
                    TextField(L10n.savedRoomsNameLabel, text: $roomName)
                        .textInputAutocapitalization(.sentences)
                }

                if let errorMessage, !errorMessage.isEmpty {
                    Section {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(L10n.savedRoomsDialogTitleCreate)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.settingsCancel) { dismiss() }
                        .disabled(isCreating)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isCreating {
                        ProgressView()
                    } else {
                        Button(L10n.savedRoomsCreateAction) {
                            create()
                        }
                        .disabled(roomName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .tint(.accentColor)
                    }
                }
            }
        }
    }

    private func create() {
        let normalized = roomName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            errorMessage = L10n.errorInvalidSavedRoomName
            return
        }

        isCreating = true
        errorMessage = nil

        Task {
            let result = await onCreateSavedRoomInviteLink(normalized)
            await MainActor.run {
                isCreating = false
                switch result {
                case .success(let link):
                    onCreatedLink(link)
                    dismiss()
                case .failure(let error):
                    let fallback = L10n.errorFailedCreateSavedRoomLink
                    errorMessage = error.localizedDescription.isEmpty ? fallback : error.localizedDescription
                }
            }
        }
    }
}

private struct StatusDot: View {
    let count: Int

    private var dotColor: Color {
        if count == 1 {
            return Color(UIColor.systemGreen)
        }
        if count >= 2 {
            return Color(UIColor.systemOrange)
        }
        return .clear
    }

    var body: some View {
        Circle()
            .fill(dotColor)
            .frame(width: 8, height: 8)
    }
}
