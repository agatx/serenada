import SwiftUI

struct FeedbackScreen: View {
    let host: String
    let appVersion: String
    let locale: String
    let onDismiss: () -> Void

    @State private var message = ""
    @State private var isSubmitting = false
    @State private var resultMessage: String?
    @State private var resultIsError = false

    private let maxLength = 2000

    var body: some View {
        Form {
            Section {
                TextEditor(text: $message)
                    .frame(minHeight: 120)
                    .onChange(of: message) { newValue in
                        if newValue.count > maxLength {
                            message = String(newValue.prefix(maxLength))
                        }
                    }

                HStack {
                    Spacer()
                    Text("\(message.count)/\(maxLength)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text(L10n.feedbackPlaceholder)
            }

            if let resultMessage {
                Text(resultMessage)
                    .font(.footnote)
                    .foregroundStyle(resultIsError ? .red : .green)
            }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if isSubmitting {
                    ProgressView()
                } else {
                    Button(L10n.feedbackSend) {
                        submitFeedback()
                    }
                    .disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .tint(.accentColor)
                }
            }
        }
        .navigationTitle(L10n.feedbackTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func submitFeedback() {
        isSubmitting = true
        resultMessage = nil

        Task {
            do {
                let statusCode = try await APIClient().submitFeedback(
                    host: host,
                    message: message.trimmingCharacters(in: .whitespacesAndNewlines),
                    locale: locale,
                    appVersion: appVersion
                )

                if statusCode == 429 {
                    resultMessage = L10n.feedbackRateLimit
                    resultIsError = true
                } else if (200...299).contains(statusCode) {
                    resultMessage = L10n.feedbackSuccess
                    resultIsError = false
                    try? await Task.sleep(nanoseconds: 800_000_000)
                    onDismiss()
                } else {
                    resultMessage = L10n.feedbackError
                    resultIsError = true
                }
            } catch {
                resultMessage = L10n.feedbackError
                resultIsError = true
            }
            isSubmitting = false
        }
    }
}
