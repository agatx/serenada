import SwiftUI

struct JoinWithCodeScreen: View {
    @Binding var roomInput: String
    let isBusy: Bool
    let statusMessage: String
    let errorMessage: String?
    let onJoinCall: () -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button(action: onBack) {
                    Label(L10n.commonBack, systemImage: "chevron.left")
                }
                Spacer()
                Button(L10n.joinWithCodeAction, action: onJoinCall)
                    .disabled(isBusy || roomInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            VStack(alignment: .leading, spacing: 12) {
                Text(L10n.joinWithCodeTitle)
                    .font(.title2.bold())

                Text(L10n.joinWithCodeHint)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                TextField(L10n.joinWithCodePlaceholder, text: $roomInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
            }

            if isBusy {
                ProgressView(statusMessage)
                    .padding(.top, 12)
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
    }
}
