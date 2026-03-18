import SwiftUI
import SerenadaCore
import SerenadaCallUI

@main
struct SampleApp: App {
    @State private var callURL: URL?

    private let serenada = SerenadaCore(config: .init(serverHost: "serenada.app"))

    var body: some Scene {
        WindowGroup {
            if let url = callURL {
                SerenadaCallFlow(url: url, onDismiss: { callURL = nil })
            } else {
                HomeView(onJoin: { url in callURL = url }, serenada: serenada)
            }
        }
    }
}

struct HomeView: View {
    let onJoin: (URL) -> Void
    let serenada: SerenadaCore

    @State private var urlText = ""
    @State private var isCreating = false

    var body: some View {
        VStack(spacing: 24) {
            Text("Serenada Sample")
                .font(.largeTitle)

            TextField("Paste a call URL", text: $urlText)
                .textFieldStyle(.roundedBorder)
                .autocapitalization(.none)
                .padding(.horizontal)

            Button("Join Call") {
                guard let url = URL(string: urlText) else { return }
                onJoin(url)
            }
            .disabled(urlText.isEmpty)

            Button("Create New Call") {
                isCreating = true
                serenada.createRoom { result in
                    isCreating = false
                    if case .success(let room) = result {
                        // In a real app, share room.url with the other party
                        print("Share this URL: \(room.url)")
                        onJoin(room.url)
                    }
                }
            }
            .disabled(isCreating)
        }
        .padding()
    }
}
