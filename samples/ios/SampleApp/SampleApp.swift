import SerenadaCallUI
import SerenadaCore
import SwiftUI

private let sampleServerHost = "serenada.app"
private let sampleCallFlowConfig = SerenadaCallFlowConfig(
    screenSharingEnabled: false,
    inviteControlsEnabled: false
)

private enum ActiveCall {
    case inviteURL(URL)
    case createdRoom(CreateRoomResult)
}

@main
struct SerenadaiOSSampleApp: App {
    @State private var activeCall: ActiveCall?
    @State private var lastCreatedRoomURL: URL?

    private let serenada = SerenadaCore(config: .init(serverHost: sampleServerHost))

    var body: some Scene {
        WindowGroup {
            Group {
                if let activeCall {
                    callFlow(for: activeCall)
                } else {
                    HomeView(
                        serenada: serenada,
                        lastCreatedRoomURL: $lastCreatedRoomURL,
                        onStartCall: { activeCall = $0 }
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func callFlow(for activeCall: ActiveCall) -> some View {
        switch activeCall {
        case .inviteURL(let url):
            SerenadaCallFlow(
                url: url,
                config: sampleCallFlowConfig,
                onDismiss: { self.activeCall = nil }
            )

        case .createdRoom(let room):
            SerenadaCallFlow(
                session: room.session,
                config: sampleCallFlowConfig,
                onDismiss: { self.activeCall = nil }
            )
        }
    }
}

private struct HomeView: View {
    let serenada: SerenadaCore
    @Binding var lastCreatedRoomURL: URL?
    let onStartCall: (ActiveCall) -> Void

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

                        Text("Minimal SwiftUI host app using `SerenadaCore` and `SerenadaCallUI` directly from this repo.")
                            .foregroundStyle(.secondary)
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Join an existing call")
                            .font(.headline)

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
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Create a new call")
                            .font(.headline)

                        Text("`createRoom()` already returns a joined session, so the sample reuses it instead of joining twice.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        Button(isCreatingRoom ? "Creating..." : "Create New Call") {
                            createRoom()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isCreatingRoom)

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
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Sample limitations")
                            .font(.headline)

                        Text("This sample hides screen sharing and waiting-room invite actions because those require app-specific broadcast extension and push wiring that belongs in a full product app.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
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

    private func joinCall() {
        let trimmed = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme != nil, url.host != nil else {
            errorMessage = "Enter a valid call URL."
            return
        }

        errorMessage = nil
        onStartCall(.inviteURL(url))
    }

    private func createRoom() {
        errorMessage = nil
        isCreatingRoom = true

        Task { @MainActor in
            do {
                let room = try await serenada.createRoom()
                isCreatingRoom = false
                lastCreatedRoomURL = room.url
                onStartCall(.createdRoom(room))
            } catch {
                isCreatingRoom = false
                errorMessage = error.localizedDescription
            }
        }
    }
}
