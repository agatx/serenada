import ReplayKit
import SwiftUI

struct BroadcastPickerButton: UIViewRepresentable {
    var onTap: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap)
    }

    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 48, height: 48))
        picker.preferredExtension = "app.serenada.ios.broadcast"
        picker.showsMicrophoneButton = false

        // Make the native button fill the view but invisible — we overlay our own icon
        if let button = picker.subviews.compactMap({ $0 as? UIButton }).first {
            button.imageView?.isHidden = true
            button.setImage(nil, for: .normal)
            button.setTitle(nil, for: .normal)

            button.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                button.leadingAnchor.constraint(equalTo: picker.leadingAnchor),
                button.trailingAnchor.constraint(equalTo: picker.trailingAnchor),
                button.topAnchor.constraint(equalTo: picker.topAnchor),
                button.bottomAnchor.constraint(equalTo: picker.bottomAnchor),
            ])

            // Fire our callback when the system picker button is tapped
            button.addTarget(context.coordinator, action: #selector(Coordinator.buttonTapped), for: .touchUpInside)
        }

        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {
        context.coordinator.onTap = onTap
    }

    final class Coordinator {
        var onTap: (() -> Void)?

        init(onTap: (() -> Void)?) {
            self.onTap = onTap
        }

        @objc func buttonTapped() {
            onTap?()
        }
    }
}
