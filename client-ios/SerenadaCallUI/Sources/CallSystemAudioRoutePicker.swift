import MediaPlayer
import SwiftUI
import UIKit

let callSystemRoutePickerWillPresentNotification = Notification.Name("app.serenada.audio.systemRoutePickerWillPresent")

struct CallSystemAudioRoutePicker: UIViewRepresentable {
    let triggerCount: Int

    func makeUIView(context: Context) -> CallSystemAudioRoutePickerView {
        CallSystemAudioRoutePickerView()
    }

    func updateUIView(_ uiView: CallSystemAudioRoutePickerView, context: Context) {
        uiView.triggerIfNeeded(triggerCount)
    }
}

final class CallSystemAudioRoutePickerView: UIView {
    private let volumeView = MPVolumeView(frame: .zero)
    private var lastTriggerCount = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        volumeView.showsVolumeSlider = false
        volumeView.alpha = 0.02
        addSubview(volumeView)
        volumeView.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            volumeView.leadingAnchor.constraint(equalTo: leadingAnchor),
            volumeView.trailingAnchor.constraint(equalTo: trailingAnchor),
            volumeView.topAnchor.constraint(equalTo: topAnchor),
            volumeView.bottomAnchor.constraint(equalTo: bottomAnchor)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func triggerIfNeeded(_ triggerCount: Int) {
        guard triggerCount != lastTriggerCount else { return }
        lastTriggerCount = triggerCount
        triggerRouteButton(attempt: 0)
    }

    private func triggerRouteButton(attempt: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + (attempt == 0 ? 0 : 0.05)) { [weak self] in
            guard let self else { return }
            guard let button = self.findRouteButton(in: self.volumeView) else {
                if attempt < 4 {
                    self.triggerRouteButton(attempt: attempt + 1)
                }
                return
            }
            button.sendActions(for: .touchUpInside)
        }
    }

    private func findRouteButton(in view: UIView) -> UIButton? {
        if let button = view as? UIButton {
            return button
        }
        for subview in view.subviews {
            if let button = findRouteButton(in: subview) {
                return button
            }
        }
        return nil
    }
}
