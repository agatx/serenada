import SwiftUI

struct JoinWithCodeScreen: View {
    @Binding var roomInput: String
    let isBusy: Bool
    let statusMessage: String
    let errorMessage: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.joinWithCodeHint)
                .font(.subheadline)
                .foregroundStyle(.secondary)

            TextField(L10n.joinWithCodePlaceholder, text: $roomInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)

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
