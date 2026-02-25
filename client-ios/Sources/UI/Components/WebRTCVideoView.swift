import SwiftUI
import UIKit
#if canImport(WebRTC)
import WebRTC
#endif

#if canImport(WebRTC)
final class MirroredRTCMTLVideoView: UIView, RTCVideoRenderer {
    private let metalView = RTCMTLVideoView(frame: .zero)
    private var pendingMirrorState: Bool?
    private(set) var isMirrored = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureMetalView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configureMetalView()
    }

    var videoContentMode: UIView.ContentMode {
        get { metalView.videoContentMode }
        set { metalView.videoContentMode = newValue }
    }

    var enabled: Bool {
        get { metalView.isEnabled }
        set { metalView.isEnabled = newValue }
    }

    var rotationOverride: NSValue? {
        get { metalView.rotationOverride }
        set { metalView.rotationOverride = newValue }
    }

    func setMirrored(_ mirrored: Bool, applyImmediately: Bool) {
        if applyImmediately {
            pendingMirrorState = nil
            applyMirrorState(mirrored)
            return
        }

        guard mirrored != isMirrored else { return }
        pendingMirrorState = mirrored
    }

    func setSize(_ size: CGSize) {
        metalView.setSize(size)
    }

    func renderFrame(_ frame: RTCVideoFrame?) {
        if let pendingMirrorState {
            self.pendingMirrorState = nil
            applyMirrorState(pendingMirrorState)
        }
        metalView.renderFrame(frame)
    }

    private func configureMetalView() {
        metalView.frame = bounds
        metalView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        metalView.clipsToBounds = true
        addSubview(metalView)
    }

    private func applyMirrorState(_ mirrored: Bool) {
        isMirrored = mirrored
        let transform = mirrored ? CGAffineTransform(scaleX: -1, y: 1) : .identity

        if Thread.isMainThread {
            metalView.transform = transform
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.metalView.transform = transform
            }
        }
    }
}
#endif

struct WebRTCVideoView: UIViewRepresentable {
    enum Kind {
        case local
        case remote
    }

    let kind: Kind
    let callManager: CallManager
    let videoContentMode: UIView.ContentMode
    let isMirrored: Bool

    init(
        kind: Kind,
        callManager: CallManager,
        videoContentMode: UIView.ContentMode = .scaleAspectFill,
        isMirrored: Bool = false
    ) {
        self.kind = kind
        self.callManager = callManager
        self.videoContentMode = videoContentMode
        self.isMirrored = isMirrored
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(kind: kind, callManager: callManager)
    }

    func makeUIView(context: Context) -> UIView {
#if canImport(WebRTC)
        switch kind {
        case .local:
            let renderer = MirroredRTCMTLVideoView(frame: .zero)
            renderer.videoContentMode = videoContentMode
            renderer.isUserInteractionEnabled = false
            renderer.setMirrored(isMirrored, applyImmediately: true)
            Task { @MainActor in
                callManager.attachLocalRenderer(renderer)
            }
            context.coordinator.renderer = renderer
            context.coordinator.isMirrored = isMirrored
            return renderer
        case .remote:
            let renderer = RTCMTLVideoView(frame: .zero)
            renderer.videoContentMode = videoContentMode
            renderer.clipsToBounds = true
            renderer.isUserInteractionEnabled = false
            Task { @MainActor in
                callManager.attachRemoteRenderer(renderer)
            }
            context.coordinator.renderer = renderer
            return renderer
        }
#else
        let placeholder = UIView(frame: .zero)
        placeholder.backgroundColor = .secondarySystemBackground

        let label = UILabel(frame: .zero)
        label.translatesAutoresizingMaskIntoConstraints = false
        label.textAlignment = .center
        label.numberOfLines = 2
        label.textColor = .label
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
        if let renderer = uiView as? MirroredRTCMTLVideoView {
            let mirrorChanged = context.coordinator.isMirrored != isMirrored
            context.coordinator.isMirrored = isMirrored

            if renderer.videoContentMode != videoContentMode {
                renderer.videoContentMode = videoContentMode
            }

            if mirrorChanged {
                // Defer mirroring until the next rendered local frame so
                // mirror state and camera feed switch together.
                renderer.setMirrored(isMirrored, applyImmediately: false)
            }
            return
        }

        if let renderer = uiView as? RTCMTLVideoView {
            if renderer.videoContentMode != videoContentMode {
                animateContentModeTransition(renderer: renderer, targetMode: videoContentMode)
            }
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
        Task { @MainActor in
            switch coordinator.kind {
            case .local:
                coordinator.callManager?.detachLocalRenderer(renderer)
            case .remote:
                coordinator.callManager?.detachRemoteRenderer(renderer)
            }
        }
#endif
    }

    final class Coordinator {
        let kind: Kind
        weak var callManager: CallManager?
        var isMirrored: Bool = false
        weak var renderer: AnyObject?

        init(kind: Kind, callManager: CallManager) {
            self.kind = kind
            self.callManager = callManager
        }
    }
}
