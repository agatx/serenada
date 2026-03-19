import ReplayKit
import SwiftUI

struct BroadcastPickerButton: View {
    let preferredExtension: String
    let systemImage: String
    let accessibilityLabel: String
    let onPrepareStart: () -> Void

    @State private var triggerCount = 0

    var body: some View {
        Button {
            onPrepareStart()
            triggerCount += 1
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 48, height: 48)
                .background(Color.black.opacity(0.45))
                .clipShape(Circle())
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .overlay {
            BroadcastPickerHost(
                preferredExtension: preferredExtension,
                triggerCount: triggerCount
            )
            .frame(width: 1, height: 1)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

private struct BroadcastPickerHost: UIViewRepresentable {
    let preferredExtension: String
    let triggerCount: Int

    func makeUIView(context: Context) -> BroadcastPickerContainerView {
        BroadcastPickerContainerView(preferredExtension: preferredExtension)
    }

    func updateUIView(_ uiView: BroadcastPickerContainerView, context: Context) {
        uiView.preferredExtension = preferredExtension
        uiView.triggerIfNeeded(triggerCount)
    }
}

private final class BroadcastPickerContainerView: UIView {
    private let pickerView = RPSystemBroadcastPickerView(frame: .zero)
    private var lastTriggerCount = 0

    var preferredExtension: String {
        get { pickerView.preferredExtension ?? "" }
        set { pickerView.preferredExtension = newValue }
    }

    init(preferredExtension: String) {
        super.init(frame: .zero)

        pickerView.preferredExtension = preferredExtension
        pickerView.showsMicrophoneButton = false
        pickerView.alpha = 0.02

        addSubview(pickerView)
        pickerView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            pickerView.leadingAnchor.constraint(equalTo: leadingAnchor),
            pickerView.trailingAnchor.constraint(equalTo: trailingAnchor),
            pickerView.topAnchor.constraint(equalTo: topAnchor),
            pickerView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func triggerIfNeeded(_ triggerCount: Int) {
        guard triggerCount != lastTriggerCount else { return }
        lastTriggerCount = triggerCount

        DispatchQueue.main.async { [weak self] in
            guard let button = self?.pickerView.subviews.compactMap({ $0 as? UIButton }).first else { return }
            button.sendActions(for: .touchUpInside)
        }
    }
}
