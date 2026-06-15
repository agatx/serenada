import Foundation
import SerenadaCallUI
import SerenadaCore
import SwiftUI

private let sampleServerHost = "serenada.app"
private let sampleCallFlowConfig = SerenadaCallFlowConfig(
    screenSharingEnabled: false,
    inviteControlsEnabled: false,
    systemPictureInPictureEnabled: true
)

private enum ActiveCall {
    case session(SerenadaSession)
}

private struct ProviderDemoSession {
    let session: SerenadaSession
    let provider: SampleMockSignalingProvider
    let coordinator: SampleAudioCoordinator
}

@main
struct SerenadaiOSSampleApp: App {
    @State private var activeCall: ActiveCall?
    @State private var activeProviderDemo: ProviderDemoSession?
    @State private var lastCreatedRoomURL: URL?

    private let serenada = SerenadaCore(config: .init(serverHost: sampleServerHost))

    var body: some Scene {
        WindowGroup {
            Group {
                if let activeCall {
                    callFlow(for: activeCall)
                } else if let activeProviderDemo {
                    ProviderDemoView(
                        session: activeProviderDemo.session,
                        provider: activeProviderDemo.provider,
                        coordinator: activeProviderDemo.coordinator,
                        onDismiss: {
                            activeProviderDemo.session.leave()
                            self.activeProviderDemo = nil
                        }
                    )
                } else {
                    HomeView(
                        serenada: serenada,
                        lastCreatedRoomURL: $lastCreatedRoomURL,
                        onStartCall: { activeCall = $0 },
                        onStartProviderDemo: {
                            let provider = SampleMockSignalingProvider()
                            let coordinator = SampleAudioCoordinator()
                            let providerCore = SerenadaCore(config: .init(signalingProvider: provider, audioCoordinator: coordinator))
                            self.activeProviderDemo = ProviderDemoSession(
                                session: providerCore.join(roomId: "sample-provider-room"),
                                provider: provider,
                                coordinator: coordinator
                            )
                        }
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func callFlow(for activeCall: ActiveCall) -> some View {
        switch activeCall {
        case .session(let session):
            SerenadaCallFlow(
                session: session,
                config: sampleCallFlowConfig,
                onEndCall: {
                    session.leave()
                    self.activeCall = nil
                },
                onDismiss: { self.activeCall = nil }
            )
        }
    }
}

private struct HomeView: View {
    let serenada: SerenadaCore
    @Binding var lastCreatedRoomURL: URL?
    let onStartCall: (ActiveCall) -> Void
    let onStartProviderDemo: () -> Void

    @State private var urlText = ""
    @State private var isCreatingRoom = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Serenada iOS Sample")
                            .font(.largeTitle.bold())

                        Text("Demonstrates both built-in signaling and a provider-mode smoke demo backed by a local in-memory SignalingProvider.")
                            .foregroundStyle(.secondary)
                    }

                    sampleCard(title: "Built-In Signaling") {
                        TextField("Paste a call URL", text: $urlText)
                            .textFieldStyle(.roundedBorder)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()

                        Button("Join Call") {
                            joinCall()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                        Button(isCreatingRoom ? "Creating..." : "Create New Call") {
                            createRoom()
                        }
                        .buttonStyle(.bordered)
                        .disabled(isCreatingRoom)
                    }

                    sampleCard(title: "Custom Provider Smoke Demo") {
                        Text("Starts a session with `SerenadaConfig(signalingProvider:)` and uses incremental peer joins plus provider-delivered peer messages without Serenada transport.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        Button("Start Mock Provider Demo") {
                            errorMessage = nil
                            onStartProviderDemo()
                        }
                        .buttonStyle(.borderedProminent)
                    }

                    if let lastCreatedRoomURL {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Latest room URL")
                                .font(.subheadline.weight(.semibold))

                            Text(lastCreatedRoomURL.absoluteString)
                                .font(.footnote.monospaced())
                                .textSelection(.enabled)

                            ShareLink(item: lastCreatedRoomURL) {
                                Label("Share Link", systemImage: "square.and.arrow.up")
                            }
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color(.secondarySystemBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.red)
                    }
                }
                .padding(24)
            }
            .navigationTitle("Sample")
        }
    }

    @ViewBuilder
    private func sampleCard(title: String, @ViewBuilder content: () -> some View) -> some View {
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

    private func joinCall() {
        let trimmed = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme != nil, url.host != nil else {
            errorMessage = "Enter a valid call URL."
            return
        }

        errorMessage = nil
        onStartCall(.session(serenada.join(url: url)))
    }

    private func createRoom() {
        errorMessage = nil
        isCreatingRoom = true

        Task { @MainActor in
            do {
                let room = try await serenada.createRoom()
                isCreatingRoom = false
                lastCreatedRoomURL = room.url
                onStartCall(.session(serenada.join(url: room.url)))
            } catch {
                isCreatingRoom = false
                errorMessage = error.localizedDescription
            }
        }
    }
}

private struct ProviderDemoView: View {
    @ObservedObject var session: SerenadaSession
    @ObservedObject var provider: SampleMockSignalingProvider
    let coordinator: SampleAudioCoordinator
    let onDismiss: () -> Void

    @State private var isExternalAudioActive = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Custom Provider Demo")
                            .font(.largeTitle.bold())

                        Text("This screen is driven by a local in-memory SignalingProvider. The session runs in provider mode only; no Serenada server APIs are used.")
                            .foregroundStyle(.secondary)
                    }

                    infoCard(title: "Session State") {
                        Text("Phase: \(session.state.phase.rawValue)")
                        Text("Participant count: \((session.state.localParticipant.cid == nil ? 0 : 1) + session.state.remoteParticipants.count)")
                        Text("Local CID: \(session.state.localParticipant.cid ?? "pending")")
                        Text("Remote peers: \(session.state.remoteParticipants.map(\.cid).joined(separator: ", ").ifEmpty("none"))")
                        Text("Is host: \(session.state.localParticipant.isHost ? "true" : "false")")
                    }

                    infoCard(title: "Audio Coordinator (External Audio)") {
                        Text("Mic Muted: \(session.isMicMuted ? "Yes" : "No")")
                        Text("Muted by External: \(session.isMicMutedByExternalAudio ? "Yes" : "No")")
                        
                        Button(action: {
                            isExternalAudioActive.toggle()
                            coordinator.simulateExternalAudio(isExternalAudioActive)
                        }) {
                            Text(isExternalAudioActive ? "End External Audio" : "Start External Audio")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(isExternalAudioActive ? .red : .blue)
                    }

                    infoCard(title: "Provider Event Log") {
                        Text(provider.eventLog.joined(separator: "\n").ifEmpty("Waiting for provider events..."))
                            .font(.footnote.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button("End Demo") {
                        onDismiss()
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(24)
            }
            .navigationTitle("Provider Demo")
        }
    }

    @ViewBuilder
    private func infoCard(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            content()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

private final class SampleMockSignalingProvider: SignalingProvider, ObservableObject {
    weak var delegate: SignalingProviderDelegate?

    @Published private(set) var eventLog: [String] = []

    private let remotePeerId = "sample-remote"
    private var localPeerId = "sample-local"
    private var scheduledTasks: [Task<Void, Never>] = []

    func connect() {
        record("connect() -> connected transport=mock")
        delegate?.signalingProviderDidConnect(ConnectionInfo(transport: "mock"))
    }

    func disconnect() {
        clearScheduledTasks()
        record("disconnect()")
    }

    func joinRoom(_ roomId: String, options: JoinOptions) {
        clearScheduledTasks()
        localPeerId = options.reconnectPeerId ?? "sample-local"
        record("joinRoom(\(roomId)) -> joined(local only)")
        delegate?.signalingProviderDidJoin(
            JoinedEvent(
                peerId: localPeerId,
                participants: [SignalingProviderParticipant(peerId: localPeerId, joinedAt: 1)],
                hostPeerId: nil,
                maxParticipants: 4
            )
        )
        schedule(after: 400) { [weak self] in
            guard let self else { return }
            self.record("peerJoined(\(self.remotePeerId))")
            self.delegate?.signalingProviderDidJoinPeer(PeerEvent(peerId: self.remotePeerId, joinedAt: 2))
        }
        schedule(after: 700) { [weak self] in
            guard let self else { return }
            self.record("message(\(self.remotePeerId) -> demo_message)")
            self.delegate?.signalingProviderDidReceiveMessage(
                PeerMessage(
                    from: self.remotePeerId,
                    type: "demo_message",
                    payload: ["text": .string("Hello from the in-memory iOS sample provider.")]
                )
            )
        }
    }

    func leaveRoom() {
        clearScheduledTasks()
        record("leaveRoom()")
        delegate?.signalingProviderDidLeavePeer(PeerEvent(peerId: remotePeerId, joinedAt: 2))
    }

    func endRoom() {
        clearScheduledTasks()
        record("endRoom() -> roomEnded")
        delegate?.signalingProviderDidEndRoom(RoomEndedEvent(by: localPeerId, reason: "mock provider demo ended"))
    }

    func sendToPeer(_ peerId: String, type: String, payload: SignalingPayload?) {
        record("sendToPeer(\(peerId), \(type))")
        echoMessage(type: type, payload: payload)
    }

    func broadcast(type: String, payload: SignalingPayload?) {
        record("broadcast(\(type))")
        echoMessage(type: type, payload: payload)
    }

    func getIceServers() async throws -> [IceServerConfig] {
        record("getIceServers() -> [] (STUN-only fallback)")
        return []
    }

    private func echoMessage(type: String, payload: SignalingPayload?) {
        schedule(after: 150) { [weak self] in
            guard let self else { return }
            self.record("message(\(self.remotePeerId) -> ack:\(type))")
            self.delegate?.signalingProviderDidReceiveMessage(
                PeerMessage(
                    from: self.remotePeerId,
                    type: "ack:\(type)",
                    payload: ["echoedPayload": payload.map(JSONValue.object) ?? .null]
                )
            )
        }
    }

    private func record(_ entry: String) {
        eventLog.append(entry)
    }

    private func schedule(after milliseconds: UInt64, operation: @escaping @MainActor () -> Void) {
        let task = Task { @MainActor in
            try? await Task.sleep(nanoseconds: milliseconds * 1_000_000)
            guard !Task.isCancelled else { return }
            operation()
        }
        scheduledTasks.append(task)
    }

    private func clearScheduledTasks() {
        scheduledTasks.forEach { $0.cancel() }
        scheduledTasks.removeAll()
    }
}

private extension String {
    func ifEmpty(_ fallback: String) -> String {
        isEmpty ? fallback : self
    }
}
