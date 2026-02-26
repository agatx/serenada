import SwiftUI

struct JoinWithCodeScreen: View {
    @Binding var roomInput: String
    let isBusy: Bool
    let statusMessage: String
    let errorMessage: String?
    let onJoin: () -> Void

    @FocusState private var isRoomInputFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(L10n.joinWithCodeHint)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField(L10n.joinWithCodePlaceholder, text: $roomInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($isRoomInputFocused)
                    .submitLabel(.go)
                    .onSubmit(onJoin)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Color(.secondarySystemBackground))
            .clipShape(Capsule())

            if isBusy {
                ProgressView(statusMessage)
            }

            if let errorMessage, !errorMessage.isEmpty {
                Text(errorMessage)
                    .foregroundStyle(.red)
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Spacer()
        }
        .padding(20)
        .onAppear {
            DispatchQueue.main.async {
                isRoomInputFocused = true
            }
        }
    }
}
