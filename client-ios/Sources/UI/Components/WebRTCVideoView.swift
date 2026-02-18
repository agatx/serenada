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
    let videoContentMode: UIView.ContentMode

    init(
        kind: Kind,
        callManager: CallManager,
        videoContentMode: UIView.ContentMode = .scaleAspectFill
    ) {
        self.kind = kind
        self.callManager = callManager
        self.videoContentMode = videoContentMode
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(kind: kind, callManager: callManager)
    }

    func makeUIView(context: Context) -> UIView {
#if canImport(WebRTC)
        let renderer = RTCMTLVideoView(frame: .zero)
        renderer.videoContentMode = videoContentMode
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

    func updateUIView(_ uiView: UIView, context: Context) {
#if canImport(WebRTC)
        if let renderer = uiView as? RTCMTLVideoView, renderer.videoContentMode != videoContentMode {
            animateContentModeTransition(renderer: renderer, targetMode: videoContentMode)
        }
#endif
    }

#if canImport(WebRTC)
    private func animateContentModeTransition(renderer: RTCMTLVideoView, targetMode: UIView.ContentMode) {
        guard renderer.window != nil, !UIAccessibility.isReduceMotionEnabled else {
            renderer.videoContentMode = targetMode
            renderer.transform = .identity
            return
        }

        renderer.layer.removeAllAnimations()

        // Match Android's tween(durationMillis = 260, FastOutSlowInEasing).
        renderer.videoContentMode = targetMode
        let startScale: CGFloat = targetMode == .scaleAspectFit ? 1.08 : 0.92
        renderer.transform = CGAffineTransform(scaleX: startScale, y: startScale)

        let animator = UIViewPropertyAnimator(
            duration: 0.26,
            controlPoint1: CGPoint(x: 0.4, y: 0.0),
            controlPoint2: CGPoint(x: 0.2, y: 1.0)
        ) {
            renderer.transform = .identity
        }
        animator.addCompletion { _ in
            renderer.transform = .identity
        }
        animator.startAnimation()
    }
#endif

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
