import SerenadaCallUI
import SerenadaCore
import SwiftUI

/// Minimal multi-call example for the Serenada iOS SDK.
///
/// Demonstrates the full ``SerenadaCallRegistry`` surface a third-party host
/// needs to juggle more than one concurrent call:
///
/// - Construct one ``SerenadaCallRegistry`` over a configured ``SerenadaCore``.
/// - `joinAndSwitch(_:)` to start a call in the foreground, `joinHeld(_:)` to
///   start one on hold (no capture, no foreground lease).
/// - Render the active call with `SerenadaCallFlow(session:)`, reading the
///   session from `registry.activeCall?.session`.
/// - List held calls and bring one forward with `switchToCall(id:)`.
/// - `holdCall(id:)`, `leaveCall(id:)`, `endCall(id:)` per call.
/// - Handle the "no active call but held calls remain" state (holding the only
///   call does NOT auto-promote another — Invariant 5).
///
/// The registry is an `ObservableObject`; the UI re-derives entirely from its
/// published `calls` / `activeCallId` / `registryOperationInProgress` axes.
struct MultiCallSampleView: View {
    @StateObject private var registry: SerenadaCallRegistry
    let onDismiss: () -> Void

    @State private var roomText = ""
    @State private var statusMessage: String?

    init(core: SerenadaCore, onDismiss: @escaping () -> Void) {
        // One registry per process owns the foreground media lease. Build it once
        // (here via `@StateObject`) and keep it for the lifetime of the screen.
        _registry = StateObject(wrappedValue: SerenadaCallRegistry(core: core))
        self.onDismiss = onDismiss
    }

    var body: some View {
        // When a call is foreground, hand its session to the prebuilt call UI.
        // `activeCall?.session` is optional (a call may be backed by a stub, or
        // there may be no active call), so it MUST be unwrapped before passing it
        // to the non-optional `SerenadaCallFlow(session:)` initializer.
        if let session = registry.activeCall?.session {
            SerenadaCallFlow(
                session: session,
                config: SerenadaCallFlowConfig(
                    screenSharingEnabled: false,
                    inviteControlsEnabled: false
                ),
                // Holding (not leaving) the active call keeps it connected and
                // surfaces the manager again so the user can pick another call.
                onEndCall: { hold(registry.activeCallId) },
                onDismiss: { hold(registry.activeCallId) }
            )
        } else {
            manager
        }
    }

    // MARK: - Manager (shown when no call is foreground)

    private var manager: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    joinCard

                    if registry.calls.isEmpty {
                        emptyState
                    } else {
                        callsList
                    }

                    if let statusMessage {
                        Text(statusMessage)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(24)
            }
            .navigationTitle("Multi-Call")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") { onDismiss() }
                }
            }
            // The registry serializes every op; reflect that in the UI so the
            // user cannot fire overlapping switches/holds.
            .disabled(registry.registryOperationInProgress)
            .overlay {
                if registry.registryOperationInProgress {
                    ProgressView().controlSize(.large)
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Multiple Calls")
                .font(.largeTitle.bold())

            Text("One SerenadaCallRegistry manages several calls. Exactly one is foreground at a time; the rest are held (connected, no capture).")
                .foregroundStyle(.secondary)
        }
    }

    private var joinCard: some View {
        card(title: "Join a Room") {
            TextField("Paste a call URL or room id", text: $roomText)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                .autocorrectionDisabled()

            // Start in the foreground (the standard "answer / place a call" flow):
            // join held, then switch to it.
            Button("Join and Switch") {
                joinAndSwitch()
            }
            .buttonStyle(.borderedProminent)
            .disabled(trimmedRoom.isEmpty)

            // Start on hold (e.g. a second incoming call you are not ready to
            // foreground yet). No capture, no foreground lease taken.
            Button("Join Held") {
                joinHeld()
            }
            .buttonStyle(.bordered)
            .disabled(trimmedRoom.isEmpty)

            // Preset rooms (same token on every device): tap to join + switch.
            // Pick the same one on two devices to connect them — no URL typing.
            Text("Preset rooms")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                ForEach(Self.presetRooms) { preset in
                    Button(String(preset.label.suffix(1))) {
                        joinPreset(preset)
                    }
                    .buttonStyle(.bordered)
                }
            }
        }
    }

    private var emptyState: some View {
        card(title: "No Calls") {
            Text("Join a room to begin. The newest active call is shown full-screen via SerenadaCallFlow.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var callsList: some View {
        card(title: "Calls (\(registry.calls.count))") {
            // `held = true` for every non-active call. When `activeCallId == nil`
            // every live call shows here — this is the "no active call but held
            // calls remain" state, reached by holding the only call (the registry
            // never auto-promotes a replacement).
            ForEach(registry.calls, id: \.id) { call in
                callRow(call)
                if call.id != registry.calls.last?.id {
                    Divider()
                }
            }
        }
    }

    @ViewBuilder
    private func callRow(_ call: ManagedCallState) -> some View {
        let isActive = call.id == registry.activeCallId
        let isEnded = Self.isEndedPhase(call.membershipPhase)

        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(call.displayName ?? call.roomId)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(statusLine(for: call, isActive: isActive, isEnded: isEnded))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                badge(for: call, isActive: isActive, isEnded: isEnded)
            }

            if let error = call.activationError {
                Text(Self.describe(error))
                    .font(.caption2)
                    .foregroundStyle(.red)
            }

            // Per-call actions. An ended call only offers "Dismiss" (drop the
            // record); a live call offers switch/hold plus leave/end.
            HStack(spacing: 8) {
                if isEnded {
                    Button("Dismiss") {
                        run { await registry.dismissEndedCall(id: call.id) }
                    }
                    .buttonStyle(.bordered)
                } else {
                    if isActive {
                        Button("Hold") { hold(call.id) }
                            .buttonStyle(.bordered)
                    } else {
                        Button("Switch") { switchTo(call.id) }
                            .buttonStyle(.borderedProminent)
                    }
                    Button("Leave") {
                        run { await registry.leaveCall(id: call.id) }
                    }
                    .buttonStyle(.bordered)
                    Button("End") {
                        run { await registry.endCall(id: call.id) }
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                }
            }
            .font(.footnote)
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func badge(for call: ManagedCallState, isActive: Bool, isEnded: Bool) -> some View {
        let (label, color): (String, Color) = {
            if isEnded { return ("Ended", .secondary) }
            if isActive { return ("Active", .green) }
            return ("Held", .orange)
        }()
        Text(label)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(color.opacity(0.18))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }

    // MARK: - Operations

    private var trimmedRoom: String {
        roomText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// A preset room: a real, permanent serenada.app room token plus a label.
    /// The same tokens are hardcoded in every sample (web/iOS/Android), so
    /// picking "Room A" on any two devices joins the same room.
    struct PresetRoom: Identifiable {
        let label: String
        let roomId: String
        var id: String { roomId }
    }

    static let presetRooms: [PresetRoom] = [
        .init(label: "Room A", roomId: "5TvGFtcHWvhSVgcy-mOnt5HYXUc"),
        .init(label: "Room B", roomId: "cB_JcTwAqXlisclO0hrFp1sz0D8"),
        .init(label: "Room C", roomId: "ditnukbowWr_IQiGbRKfO_Y-XEo"),
        .init(label: "Room D", roomId: "5DGYugsbA1e3FohkkYs7_f9VJnU"),
        .init(label: "Room E", roomId: "RshEuP_IW8eWOi73pH3bp61nCE0"),
    ]

    /// Build a ``RoomRef`` from the text field: a full URL when it parses as one,
    /// otherwise a bare room id. The registry canonicalizes either form.
    private func makeRoomRef() -> RoomRef? {
        let text = trimmedRoom
        guard !text.isEmpty else { return nil }
        if let url = URL(string: text), url.scheme != nil, url.host != nil {
            return RoomRef(url: url, displayName: "Me")
        }
        return RoomRef(roomId: text, displayName: "Me")
    }

    /// Join held, then bring the call to the foreground (the standard
    /// "answer / place a call" flow). Handles every `JoinAndSwitchResult` case.
    private func joinAndSwitch() {
        guard let room = makeRoomRef() else {
            statusMessage = "Enter a valid call URL or room id."
            return
        }
        roomText = ""
        performJoinAndSwitch(room)
    }

    /// Join one of the preset rooms (same token on every device). Pick the same
    /// preset on two devices to connect them — no URL typing required.
    private func joinPreset(_ preset: PresetRoom) {
        performJoinAndSwitch(RoomRef(roomId: preset.roomId, displayName: preset.label))
    }

    private func performJoinAndSwitch(_ room: RoomRef) {
        statusMessage = nil
        Task { @MainActor in
            switch await registry.joinAndSwitch(room) {
            case .active:
                statusMessage = nil
            case .needsPermission:
                statusMessage = "Joined on hold. Grant permission, then switch to it."
            case let .failed(_, error):
                statusMessage = Self.describe(error)
            }
        }
    }

    /// Join a room WITHOUT taking the foreground (no capture, no lease). Handles
    /// every `JoinResult` case.
    private func joinHeld() {
        guard let room = makeRoomRef() else {
            statusMessage = "Enter a valid call URL or room id."
            return
        }
        statusMessage = nil
        roomText = ""
        Task { @MainActor in
            switch await registry.joinHeld(room) {
            case .joined:
                statusMessage = "Joined on hold."
            case let .failed(_, error):
                statusMessage = Self.describe(error)
            }
        }
    }

    private func switchTo(_ id: CallId) {
        run {
            // `switchToCall` returns `.active`, `.needsPermission` (old call is
            // untouched; prompt for permission, then retry switchToCall), or `.failed`.
            switch await registry.switchToCall(id: id) {
            case .active:
                statusMessage = nil
            case .needsPermission:
                statusMessage = "Grant mic/camera permission, then switch again."
            case let .failed(error):
                statusMessage = Self.describe(error)
            }
        }
    }

    /// Hold the given call (if any). Holding the active call drains it and clears
    /// `activeCallId` with no auto-promote, returning to this manager screen.
    private func hold(_ id: CallId?) {
        guard let id else { return }
        run { await registry.holdCall(id: id) }
    }

    private func run(_ op: @escaping () async -> Void) {
        Task { @MainActor in await op() }
    }

    // MARK: - Presentation helpers

    private func statusLine(for call: ManagedCallState, isActive: Bool, isEnded: Bool) -> String {
        if isEnded { return "Ended - \(call.roomId)" }
        let peers = "\(call.participantCount) participant\(call.participantCount == 1 ? "" : "s")"
        return "\(call.membershipPhase.rawValue) - \(peers)"
    }

    @ViewBuilder
    private func card(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private static func isEndedPhase(_ phase: SerenadaCallPhase) -> Bool {
        phase == .ending || phase == .idle || phase == .error
    }

    /// Render a `CallActivationError` for display. Mirrors the real enum cases so
    /// a host sees an accurate reason.
    private static func describe(_ error: CallActivationError) -> String {
        switch error {
        case let .needsPermission(caps):
            let names = caps.map(\.rawValue).joined(separator: ", ")
            return "Needs permission: \(names)"
        case let .activationFailed(reason):
            return "Activation failed: \(reason)"
        case .releaseTimedOut:
            return "Releasing the previous call timed out"
        case let .joinFailed(reason):
            return "Join failed: \(reason)"
        case .leaseUnavailable:
            return "Audio is in use by another call"
        }
    }
}
