import SwiftUI

struct JoinScreen: View {
    let isBusy: Bool
    let statusMessage: String
    let recentCalls: [RecentCall]
    let savedRooms: [SavedRoom]
    let areSavedRoomsShownFirst: Bool
    let roomStatuses: [String: RoomStatus]
    let serverHost: String
    let onOpenJoinWithCode: () -> Void
    let onOpenSettings: () -> Void
    let onStartCall: () -> Void
    let onJoinRecentCall: (RecentCall) -> Void
    let onJoinSavedRoom: (SavedRoom) -> Void
    let onRemoveRecentCall: (String) -> Void
    let onSaveRoom: (String, String) -> Void
    let onCreateSavedRoomInviteLink: (String) async -> Result<String, Error>
    let onRemoveSavedRoom: (String) -> Void

    @State private var savedRoomSheetContext: SavedRoomSheetContext?
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
        .sheet(item: $savedRoomSheetContext) { context in
            CreateSavedRoomSheet(
                mode: context.mode,
                initialName: context.initialName,
                onSaveRoom: onSaveRoom,
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
            serverHost: serverHost,
            isBusy: isBusy,
            onCreate: {
                savedRoomSheetContext = SavedRoomSheetContext(mode: .create, initialName: "")
            },
            onJoinSavedRoom: onJoinSavedRoom,
            onRenameSavedRoom: { room in
                savedRoomSheetContext = SavedRoomSheetContext(mode: .rename(roomId: room.roomId), initialName: room.name)
            },
            onShareSavedRoom: { room in
                shareLink = buildSavedRoomShareLink(for: room)
            },
            onRemoveSavedRoom: onRemoveSavedRoom
        )
    }

    private var recentCallsSection: some View {
        RecentCallsSection(
            calls: recentCalls,
            roomStatuses: roomStatuses,
            serverHost: serverHost,
            savedRoomNameById: Dictionary(uniqueKeysWithValues: savedRooms.map { ($0.roomId, $0.name) }),
            isBusy: isBusy,
            onJoinRecentCall: onJoinRecentCall,
            onSaveRecentCall: { roomId, existingName in
                if let existingName {
                    savedRoomSheetContext = SavedRoomSheetContext(mode: .rename(roomId: roomId), initialName: existingName)
                } else {
                    savedRoomSheetContext = SavedRoomSheetContext(mode: .save(roomId: roomId), initialName: "")
                }
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
    let roomStatuses: [String: RoomStatus]
    let serverHost: String
    let savedRoomNameById: [String: String]
    let isBusy: Bool
    let onJoinRecentCall: (RecentCall) -> Void
    let onSaveRecentCall: (String, String?) -> Void
    let onRemoveRecentCall: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(L10n.recentCallsTitle, systemImage: "clock")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)
                .padding(.top, 12)
                .padding(.bottom, 6)

            VStack(alignment: .leading, spacing: 0) {
                ForEach(calls) { call in
                    let roomName = savedRoomNameById[call.roomId]
                    let hostLabel: String? = {
                        guard let h = call.host, h.compare(serverHost, options: .caseInsensitive) != .orderedSame else { return nil }
                        return h
                    }()
                    Button {
                        onJoinRecentCall(call)
                    } label: {
                        HStack(spacing: 12) {
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

                                if let hostLabel {
                                    Text(hostLabel)
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            HStack(alignment: .center, spacing: 8) {
                                Text(formatDuration(call.durationSeconds))
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)

                                StatusDot(status: roomStatuses[call.roomId])
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .frame(maxWidth: .infinity, alignment: .leading)
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
        if seconds < 120 {
            let minutes = seconds / 60
            let remainderSeconds = seconds % 60
            if minutes == 0 { return "\(remainderSeconds)s" }
            return "\(minutes)m \(remainderSeconds)s"
        }
        let minutes = Int((Double(seconds) / 60.0).rounded())
        return "\(minutes)m"
    }
}

private struct SavedRoomsSection: View {
    let rooms: [SavedRoom]
    let roomStatuses: [String: RoomStatus]
    let serverHost: String
    let isBusy: Bool
    let onCreate: () -> Void
    let onJoinSavedRoom: (SavedRoom) -> Void
    let onRenameSavedRoom: (SavedRoom) -> Void
    let onShareSavedRoom: (SavedRoom) -> Void
    let onRemoveSavedRoom: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label(rooms.isEmpty ? L10n.savedRoomsTitleEmpty : L10n.savedRoomsTitle, systemImage: "bookmark.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                Button(L10n.savedRoomsCreate, action: onCreate)
                    .disabled(isBusy)
            }
            .padding(.horizontal, 4)
            .padding(.top, 12)
            .padding(.bottom, 6)

            VStack(alignment: .leading, spacing: 0) {
                ForEach(rooms) { room in
                    let hostLabel: String? = {
                        guard let h = room.host, h.compare(serverHost, options: .caseInsensitive) != .orderedSame else { return nil }
                        return h
                    }()
                    Button {
                        onJoinSavedRoom(room)
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(room.name)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Text(formatLastJoined(room.lastJoinedAt))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                if let hostLabel {
                                    Text(hostLabel)
                                        .font(.caption)
                                        .foregroundStyle(.tertiary)
                                        .lineLimit(1)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Spacer()

                            StatusDot(status: roomStatuses[room.roomId])
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            onShareSavedRoom(room)
                        } label: {
                            Label(L10n.savedRoomsShareLinkChooser, systemImage: "square.and.arrow.up")
                        }
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
    let mode: SavedRoomSheetMode
    let initialName: String
    let onSaveRoom: (String, String) -> Void
    let onCreateSavedRoomInviteLink: (String) async -> Result<String, Error>
    let onCreatedLink: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @FocusState private var isRoomNameFieldFocused: Bool
    @State private var roomName: String
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    init(
        mode: SavedRoomSheetMode,
        initialName: String,
        onSaveRoom: @escaping (String, String) -> Void,
        onCreateSavedRoomInviteLink: @escaping (String) async -> Result<String, Error>,
        onCreatedLink: @escaping (String) -> Void
    ) {
        self.mode = mode
        self.initialName = initialName
        self.onSaveRoom = onSaveRoom
        self.onCreateSavedRoomInviteLink = onCreateSavedRoomInviteLink
        self.onCreatedLink = onCreatedLink
        _roomName = State(initialValue: initialName)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(formTitle) {
                    TextField(L10n.savedRoomsNameLabel, text: $roomName)
                        .textInputAutocapitalization(.sentences)
                        .focused($isRoomNameFieldFocused)
                }

                if let errorMessage, !errorMessage.isEmpty {
                    Section {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(formTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.settingsCancel) { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSubmitting {
                        ProgressView()
                    } else {
                        Button(confirmTitle) {
                            submit()
                        }
                        .disabled(roomName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .tint(.accentColor)
                    }
                }
            }
        }
        .onAppear {
            DispatchQueue.main.async {
                isRoomNameFieldFocused = true
            }
        }
    }

    private var formTitle: String {
        switch mode {
        case .create:
            return L10n.savedRoomsDialogTitleCreate
        case .save:
            return L10n.savedRoomsDialogTitleNew
        case .rename:
            return L10n.savedRoomsDialogTitleRename
        }
    }

    private var confirmTitle: String {
        switch mode {
        case .create:
            return L10n.savedRoomsCreateAction
        case .save, .rename:
            return L10n.settingsSave
        }
    }

    private func submit() {
        let normalized = roomName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            errorMessage = L10n.errorInvalidSavedRoomName
            return
        }

        switch mode {
        case .create:
            isSubmitting = true
        case .save(let roomId), .rename(let roomId):
            onSaveRoom(roomId, normalized)
            dismiss()
            return
        }

        errorMessage = nil

        Task {
            let result = await onCreateSavedRoomInviteLink(normalized)
            await MainActor.run {
                isSubmitting = false
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

private enum SavedRoomSheetMode {
    case create
    case save(roomId: String)
    case rename(roomId: String)
}

private struct SavedRoomSheetContext: Identifiable {
    let id = UUID()
    let mode: SavedRoomSheetMode
    let initialName: String
}

private func buildSavedRoomShareLink(for room: SavedRoom) -> String {
    let resolvedHost = DeepLinkParser.normalizeHostValue(room.host) ?? AppConstants.defaultHost
    let appLinkHost = resolvedHost == AppConstants.ruHost ? AppConstants.ruHost : AppConstants.defaultHost

    var components = URLComponents()
    components.scheme = "https"
    components.host = appLinkHost
    components.path = "/call/\(room.roomId)"
    components.queryItems = [
        URLQueryItem(name: "host", value: resolvedHost),
        URLQueryItem(name: "name", value: room.name)
    ]

    return components.url?.absoluteString ?? "https://\(appLinkHost)/call/\(room.roomId)"
}

private struct StatusDot: View {
    let status: RoomStatus?

    var body: some View {
        Group {
            switch RoomStatuses.indicatorState(for: status) {
            case .hidden:
                EmptyView()
            case .waiting:
                Circle()
                    .fill(Color(UIColor.systemGreen))
                    .frame(width: 8, height: 8)
            case .full:
                Circle()
                    .fill(Color(UIColor.systemOrange))
                    .frame(width: 8, height: 8)
            }
        }
    }
}
