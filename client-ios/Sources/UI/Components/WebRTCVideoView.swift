import SwiftUI
import UIKit
#if canImport(WebRTC)
import WebRTC
#endif

struct WebRTCVideoView: UIViewRepresentable {
    enum Kind {
        case local
        case remote
    }

    let kind: Kind
    let callManager: CallManager

    func makeCoordinator() -> Coordinator {
        Coordinator(kind: kind, callManager: callManager)
    }

    func makeUIView(context: Context) -> UIView {
#if canImport(WebRTC)
        let renderer = RTCMTLVideoView(frame: .zero)
        renderer.videoContentMode = .scaleAspectFill
        renderer.clipsToBounds = true

        switch kind {
        case .local:
            callManager.attachLocalRenderer(renderer)
        case .remote:
            callManager.attachRemoteRenderer(renderer)
        }

        context.coordinator.renderer = renderer
        return renderer
#else
        let placeholder = UIView(frame: .zero)
        placeholder.backgroundColor = UIColor(red: 0.12, green: 0.12, blue: 0.12, alpha: 1)

        let label = UILabel(frame: .zero)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.textAlignment = .center
        label.numberOfLines = 2
        label.textColor = .white
        label.font = UIFont.systemFont(ofSize: 13, weight: .medium)
        label.text = kind == .local ? "Local video\n(WebRTC stub)" : "Remote video\n(WebRTC stub)"
        placeholder.addSubview(label)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: placeholder.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: placeholder.centerYAnchor)
        ])

        return placeholder
#endif
    }

    func updateUIView(_ uiView: UIView, context: Context) {}

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
#if canImport(WebRTC)
        guard let renderer = coordinator.renderer else { return }
        switch coordinator.kind {
        case .local:
            coordinator.callManager?.detachLocalRenderer(renderer)
        case .remote:
            coordinator.callManager?.detachRemoteRenderer(renderer)
        }
#endif
    }

    final class Coordinator {
        let kind: Kind
        weak var callManager: CallManager?
#if canImport(WebRTC)
        weak var renderer: RTCMTLVideoView?
#endif

        init(kind: Kind, callManager: CallManager) {
            self.kind = kind
            self.callManager = callManager
        }
    }
}
