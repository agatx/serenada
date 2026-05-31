import AVFoundation
import AVKit
import CoreImage
import SerenadaCore
import SwiftUI
import UIKit
#if canImport(WebRTC)
@preconcurrency import WebRTC
#endif

enum SystemPictureInPictureSource: Equatable {
    case local
    case remote(cid: String?)
}

func selectSystemPictureInPictureSource(
    localSourceId: String?,
    localIsPrimary: Bool,
    localVideoEnabled: Bool,
    remoteParticipants: [RemoteParticipant],
    preferredSourceIds: [String?] = [],
    sourceIdForPreferredSourceId: (String) -> String? = { $0 }
) -> SystemPictureInPictureSource {
    let remoteCids = Set(remoteParticipants.map(\.cid))

    for preferredSourceId in preferredSourceIds {
        guard let preferredSourceId else { continue }
        guard let sourceId = sourceIdForPreferredSourceId(preferredSourceId) else { continue }
        if let localSourceId, sourceId == localSourceId {
            if localVideoEnabled {
                return .local
            }
            continue
        }
        guard remoteCids.contains(sourceId) else {
            continue
        }
        return .remote(cid: sourceId)
    }

    if localIsPrimary && localVideoEnabled {
        return .local
    }
    if let remote = remoteParticipants.first {
        return .remote(cid: remote.cid)
    }
    if localVideoEnabled {
        return .local
    }
    return .remote(cid: nil)
}

enum SystemPictureInPictureCoordinateSpace {
    static let name = "app.serenada.callui.system-pip"
}

struct SystemPictureInPictureSourceFrameReporter: View {
    let onFrameChanged: ((CGRect?) -> Void)?

    var body: some View {
        GeometryReader { proxy in
            Color.clear.preference(
                key: SystemPictureInPictureSourceFramePreferenceKey.self,
                value: proxy.frame(in: .named(SystemPictureInPictureCoordinateSpace.name))
            )
        }
        .onPreferenceChange(SystemPictureInPictureSourceFramePreferenceKey.self) { frame in
            guard let onFrameChanged else { return }
            if let frame, !frame.isEmpty {
                onFrameChanged(frame)
            } else {
                onFrameChanged(nil)
            }
        }
    }
}

private struct SystemPictureInPictureSourceFramePreferenceKey: PreferenceKey {
    static let defaultValue: CGRect? = nil

    static func reduce(value: inout CGRect?, nextValue: () -> CGRect?) {
        value = nextValue() ?? value
    }
}

extension View {
    @ViewBuilder
    func systemPictureInPictureSourceFrame(
        onChange: ((CGRect?) -> Void)?
    ) -> some View {
        if onChange == nil {
            self
        } else {
            background(SystemPictureInPictureSourceFrameReporter(onFrameChanged: onChange))
        }
    }
}

private struct SystemPictureInPictureParticipant {
    let source: SystemPictureInPictureSource
    let videoEnabled: Bool
    let displayName: String?
    let peerId: String?
    let callStartedAtMs: Int64?
}

private final class SystemPictureInPictureSourceView: UIView {
    private let contentView = UIView(frame: .zero)
    private let avatarImageView = UIImageView(frame: .zero)
    private let initialsLabel = UILabel(frame: .zero)
    private let nameLabel = UILabel(frame: .zero)
    private let timerLabel = UILabel(frame: .zero)

    private var avatarSizeConstraint: NSLayoutConstraint?
    private var contentWidthConstraint: NSLayoutConstraint?
    private var nameTopConstraint: NSLayoutConstraint?
    private var timerTopConstraint: NSLayoutConstraint?
    private var initialsFontSize: CGFloat = 24
    private var nameFontSize: CGFloat = 12
    private var timerFontSize: CGFloat = 12
    private var participant: SystemPictureInPictureParticipant?
    private var avatarImage: UIImage?
    private var timer: Timer?
    private var fallbackStartedAtMs = Int64(Date().timeIntervalSince1970 * 1000)

    override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    deinit {
        timer?.invalidate()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateLayoutMetrics()
    }

    func update(
        participant: SystemPictureInPictureParticipant,
        avatarImage: UIImage?,
        placeholderVisible: Bool
    ) {
        self.participant = participant
        self.avatarImage = avatarImage
        applyContent()
        setPlaceholderVisible(placeholderVisible && !participant.videoEnabled, animated: false)
        updateTimer()
    }

    func setPlaceholderVisible(_ visible: Bool, animated: Bool) {
        let changes = {
            self.contentView.alpha = visible ? 1 : 0
        }

        contentView.isHidden = false
        if animated {
            UIView.animate(withDuration: 0.08, animations: changes) { _ in
                self.contentView.isHidden = !visible
                self.updateTimerState()
            }
        } else {
            changes()
            contentView.isHidden = !visible
            updateTimerState()
        }
    }

    private func configure() {
        backgroundColor = .clear
        isUserInteractionEnabled = false

        contentView.translatesAutoresizingMaskIntoConstraints = false
        contentView.backgroundColor = .clear
        contentView.alpha = 0
        contentView.isHidden = true
        addSubview(contentView)

        avatarImageView.translatesAutoresizingMaskIntoConstraints = false
        avatarImageView.contentMode = .scaleAspectFill
        avatarImageView.clipsToBounds = true
        avatarImageView.backgroundColor = UIColor(white: 0.16, alpha: 1)

        initialsLabel.translatesAutoresizingMaskIntoConstraints = false
        initialsLabel.textAlignment = .center
        initialsLabel.textColor = UIColor.white.withAlphaComponent(0.9)
        initialsLabel.font = UIFont.systemFont(ofSize: initialsFontSize, weight: .heavy)

        nameLabel.translatesAutoresizingMaskIntoConstraints = false
        nameLabel.textAlignment = .center
        nameLabel.textColor = UIColor.white
        nameLabel.font = UIFont.systemFont(ofSize: nameFontSize, weight: .bold)
        nameLabel.numberOfLines = 1
        nameLabel.adjustsFontSizeToFitWidth = true
        nameLabel.minimumScaleFactor = 0.7

        timerLabel.translatesAutoresizingMaskIntoConstraints = false
        timerLabel.textAlignment = .center
        timerLabel.textColor = UIColor.white.withAlphaComponent(0.5)
        timerLabel.font = UIFont.monospacedDigitSystemFont(ofSize: timerFontSize, weight: .medium)
        timerLabel.numberOfLines = 1

        contentView.addSubview(avatarImageView)
        contentView.addSubview(initialsLabel)
        contentView.addSubview(nameLabel)
        contentView.addSubview(timerLabel)

        let avatarSizeConstraint = avatarImageView.widthAnchor.constraint(equalToConstant: 56)
        let contentWidthConstraint = contentView.widthAnchor.constraint(greaterThanOrEqualTo: avatarImageView.widthAnchor)
        let nameTopConstraint = nameLabel.topAnchor.constraint(equalTo: avatarImageView.bottomAnchor, constant: 18)
        let timerTopConstraint = timerLabel.topAnchor.constraint(equalTo: nameLabel.bottomAnchor, constant: 10)
        self.avatarSizeConstraint = avatarSizeConstraint
        self.contentWidthConstraint = contentWidthConstraint
        self.nameTopConstraint = nameTopConstraint
        self.timerTopConstraint = timerTopConstraint

        NSLayoutConstraint.activate([
            contentView.centerXAnchor.constraint(equalTo: centerXAnchor),
            contentView.centerYAnchor.constraint(equalTo: centerYAnchor),
            contentView.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor),
            contentView.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
            contentView.topAnchor.constraint(greaterThanOrEqualTo: topAnchor),
            contentView.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor),
            contentWidthConstraint,

            avatarImageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            avatarImageView.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            avatarSizeConstraint,
            avatarImageView.heightAnchor.constraint(equalTo: avatarImageView.widthAnchor),

            initialsLabel.centerXAnchor.constraint(equalTo: avatarImageView.centerXAnchor),
            initialsLabel.centerYAnchor.constraint(equalTo: avatarImageView.centerYAnchor),

            nameLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            nameLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            nameTopConstraint,
            nameLabel.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, constant: -24),

            timerLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            timerLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            timerTopConstraint,
            timerLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor)
        ])
    }

    private func updateLayoutMetrics() {
        let width = max(bounds.width, 1)
        let height = max(bounds.height, 1)
        let scale = min(1, width / 140, height / 244)
        let avatarSize = 140 * scale
        let nameSize = 34 * scale
        let timerSize = 28 * scale

        avatarSizeConstraint?.constant = avatarSize
        nameTopConstraint?.constant = 18 * scale
        timerTopConstraint?.constant = 10 * scale
        avatarImageView.layer.cornerRadius = avatarSize / 2
        initialsFontSize = avatarSize * 0.41
        nameFontSize = nameSize
        timerFontSize = timerSize
        initialsLabel.font = UIFont.systemFont(ofSize: initialsFontSize, weight: .heavy)
        nameLabel.font = UIFont.systemFont(ofSize: nameFontSize, weight: .bold)
        timerLabel.font = UIFont.monospacedDigitSystemFont(ofSize: timerFontSize, weight: .medium)
    }

    private func applyContent() {
        guard let participant else { return }
        let displayName = participant.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        nameLabel.text = displayName?.isEmpty == false ? displayName : nil

        if let avatarImage {
            avatarImageView.image = avatarImage
            initialsLabel.text = nil
        } else {
            avatarImageView.image = nil
            let initials = initialsFor(displayName: displayName)
            initialsLabel.text = initials.isEmpty ? nil : initials
        }
    }

    private func updateTimerState() {
        if contentView.isHidden {
            timer?.invalidate()
            timer = nil
        } else if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                self?.updateTimer()
            }
        }
        updateTimer()
    }

    private func updateTimer() {
        guard let participant else {
            timerLabel.text = nil
            return
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        timerLabel.text = formatCallElapsed(
            startedAtMs: participant.callStartedAtMs,
            fallbackStartedAtMs: fallbackStartedAtMs,
            nowMs: nowMs
        )
    }
}

struct SystemPictureInPictureLayer: View {
    let enabled: Bool
    let uiState: CallUiState
    let source: SystemPictureInPictureSource
    let sourceFrame: CGRect?
    let rendererProvider: CallRendererProvider

    @Environment(\.avatarCache) private var avatarCache

    var body: some View {
        let participant = selectedParticipant
        let avatarImage = participant.peerId.flatMap { avatarCache?.image(for: $0) }

        GeometryReader { geometry in
            let frame = normalizedSourceFrame(in: geometry.size)
            SystemPictureInPictureHost(
                enabled: enabled && uiState.phase.isSystemPictureInPicturePhase,
                participant: participant,
                avatarImage: avatarImage,
                rendererProvider: rendererProvider
            )
            .frame(width: frame.width, height: frame.height)
            .position(x: frame.midX, y: frame.midY)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .task(id: participant.peerId) {
                guard let peerId = participant.peerId else { return }
                await MainActor.run {
                    avatarCache?.load(peerId: peerId)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func normalizedSourceFrame(in fallbackSize: CGSize) -> CGRect {
        guard let sourceFrame,
              !sourceFrame.isEmpty,
              sourceFrame.width.isFinite,
              sourceFrame.height.isFinite else {
            return CGRect(origin: .zero, size: fallbackSize)
        }
        return sourceFrame
    }

    private var selectedParticipant: SystemPictureInPictureParticipant {
        switch source {
        case .local:
            return SystemPictureInPictureParticipant(
                source: .local,
                videoEnabled: uiState.localVideoEnabled,
                displayName: uiState.localDisplayName,
                peerId: uiState.localCid,
                callStartedAtMs: uiState.callStartedAtMs
            )
        case .remote(let cid):
            let remote = cid.flatMap { selectedCid in
                uiState.remoteParticipants.first { $0.cid == selectedCid }
            } ?? uiState.remoteParticipants.first
            return SystemPictureInPictureParticipant(
                source: .remote(cid: remote?.cid ?? cid),
                videoEnabled: remote?.videoEnabled == true,
                displayName: remote?.displayName,
                peerId: remote?.peerId,
                callStartedAtMs: uiState.callStartedAtMs
            )
        }
    }
}

private struct SystemPictureInPictureHost: UIViewRepresentable {
    let enabled: Bool
    let participant: SystemPictureInPictureParticipant
    let avatarImage: UIImage?
    let rendererProvider: CallRendererProvider

    func makeCoordinator() -> Coordinator {
        Coordinator(rendererProvider: rendererProvider)
    }

    func makeUIView(context: Context) -> SystemPictureInPictureSourceView {
        let view = SystemPictureInPictureSourceView(frame: .zero)
        context.coordinator.configure(sourceView: view)
        return view
    }

    func updateUIView(_ uiView: SystemPictureInPictureSourceView, context: Context) {
        context.coordinator.rendererProvider = rendererProvider
        context.coordinator.update(
            enabled: enabled,
            participant: participant,
            avatarImage: avatarImage
        )
    }

    static func dismantleUIView(_ uiView: SystemPictureInPictureSourceView, coordinator: Coordinator) {
        coordinator.cleanup()
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency AVPictureInPictureControllerDelegate {
        weak var rendererProvider: CallRendererProvider?

        private weak var sourceView: SystemPictureInPictureSourceView?
        private var controller: AVPictureInPictureController?
        private var contentController: SystemPictureInPictureContentController?
        private var attachedSource: SystemPictureInPictureSource?
        private var currentParticipant: SystemPictureInPictureParticipant?
        private var currentAvatarImage: UIImage?

        init(rendererProvider: CallRendererProvider) {
            self.rendererProvider = rendererProvider
            super.init()
        }

        func configure(sourceView: SystemPictureInPictureSourceView) {
            self.sourceView = sourceView
            ensureController()
        }

        func update(enabled: Bool, participant: SystemPictureInPictureParticipant, avatarImage: UIImage?) {
            currentParticipant = participant
            currentAvatarImage = avatarImage
            sourceView?.update(
                participant: participant,
                avatarImage: avatarImage,
                placeholderVisible: controller?.isPictureInPictureActive == true
            )

            guard enabled else {
                if controller?.isPictureInPictureActive == true {
                    controller?.stopPictureInPicture()
                }
                sourceView?.setPlaceholderVisible(false, animated: false)
                detachRenderer()
                contentController?.update(participant: participant, avatarImage: avatarImage)
                return
            }

            ensureController()
            contentController?.update(participant: participant, avatarImage: avatarImage)
            if controller?.isPictureInPictureActive == true {
                attachRenderer(participant: participant)
            } else {
                detachRenderer()
            }
        }

        func detachRenderer() {
#if canImport(WebRTC)
            guard let videoView = contentController?.videoView else {
                attachedSource = nil
                return
            }
            guard let source = attachedSource else { return }
            let provider = rendererProvider
            switch source {
            case .local:
                provider?.detachLocalRenderer(videoView)
            case .remote(let cid):
                if let cid {
                    provider?.detachRemoteRenderer(videoView, forCid: cid)
                } else {
                    provider?.detachRemoteRenderer(videoView)
                }
            }
            attachedSource = nil
#else
            attachedSource = nil
#endif
        }

        func cleanup() {
            if controller?.isPictureInPictureActive == true {
                controller?.stopPictureInPicture()
            }
            sourceView?.setPlaceholderVisible(false, animated: false)
            detachRenderer()
            controller?.delegate = nil
            controller = nil
            contentController = nil
            currentParticipant = nil
            currentAvatarImage = nil
        }

        private func ensureController() {
            guard controller == nil,
                  AVPictureInPictureController.isPictureInPictureSupported(),
                  let sourceView else {
                return
            }

            let contentController = SystemPictureInPictureContentController()
            let contentSource = AVPictureInPictureController.ContentSource(
                activeVideoCallSourceView: sourceView,
                contentViewController: contentController
            )
            let controller = AVPictureInPictureController(contentSource: contentSource)
            controller.canStartPictureInPictureAutomaticallyFromInline = true
            controller.delegate = self
            self.contentController = contentController
            self.controller = controller
        }

        private func attachRenderer(participant: SystemPictureInPictureParticipant) {
#if canImport(WebRTC)
            guard participant.videoEnabled else {
                detachRenderer()
                return
            }
            guard let videoView = contentController?.videoView else { return }
            guard participant.source != attachedSource else { return }
            detachRenderer()
            let provider = rendererProvider
            let source = participant.source
            switch source {
            case .local:
                provider?.attachLocalRenderer(videoView)
            case .remote(let cid):
                if let cid {
                    provider?.attachRemoteRenderer(videoView, forCid: cid)
                } else {
                    provider?.attachRemoteRenderer(videoView)
                }
            }
            attachedSource = source
#endif
        }

        func pictureInPictureController(
            _ pictureInPictureController: AVPictureInPictureController,
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
        ) {
            completionHandler(true)
        }

        func pictureInPictureControllerWillStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
            updateSourcePlaceholder(visible: true, animated: false)
            attachCurrentRenderer()
        }

        func pictureInPictureControllerDidStartPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
            updateSourcePlaceholder(visible: true, animated: false)
            attachCurrentRenderer()
        }

        func pictureInPictureControllerWillStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
            updateSourcePlaceholder(visible: true, animated: false)
        }

        func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
            updateSourcePlaceholder(visible: false, animated: true)
            detachRenderer()
        }

        private func updateSourcePlaceholder(visible: Bool, animated: Bool) {
            guard let currentParticipant else {
                sourceView?.setPlaceholderVisible(false, animated: animated)
                return
            }
            guard visible else {
                sourceView?.setPlaceholderVisible(false, animated: animated)
                return
            }
            sourceView?.update(
                participant: currentParticipant,
                avatarImage: currentAvatarImage,
                placeholderVisible: true
            )
        }

        private func attachCurrentRenderer() {
            guard let currentParticipant else { return }
            attachRenderer(participant: currentParticipant)
        }
    }
}

private final class SystemPictureInPictureContentController: AVPictureInPictureVideoCallViewController {
    private static let callAspectContentSize = CGSize(width: 360, height: 640)

#if canImport(WebRTC)
    let videoView = SystemPictureInPictureVideoView(frame: .zero)
#else
    let videoView = UIView(frame: .zero)
#endif

    private let placeholderView = UIView(frame: .zero)
    private let avatarImageView = UIImageView(frame: .zero)
    private let initialsLabel = UILabel(frame: .zero)
    private let nameLabel = UILabel(frame: .zero)
    private var avatarSizeConstraint: NSLayoutConstraint?
    private var avatarCenterYConstraint: NSLayoutConstraint?
    private var nameTopConstraint: NSLayoutConstraint?

    override init(nibName nibNameOrNil: String?, bundle nibBundleOrNil: Bundle?) {
        super.init(nibName: nibNameOrNil, bundle: nibBundleOrNil)
        preferredContentSize = Self.callAspectContentSize
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        preferredContentSize = Self.callAspectContentSize
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configureVideoView()
        configurePlaceholderView()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updatePlaceholderLayoutMetrics()
    }

    func update(participant: SystemPictureInPictureParticipant, avatarImage: UIImage?) {
        let showsVideo = participant.videoEnabled
        videoView.isHidden = !showsVideo
        placeholderView.isHidden = showsVideo

        let displayName = participant.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        nameLabel.text = displayName?.isEmpty == false ? displayName : nil

        if let avatarImage {
            avatarImageView.image = avatarImage
            initialsLabel.text = nil
        } else {
            avatarImageView.image = nil
            let initials = initialsFor(displayName: displayName)
            initialsLabel.text = initials.isEmpty ? "•" : initials
        }
    }

    private func configureVideoView() {
        videoView.translatesAutoresizingMaskIntoConstraints = false
        videoView.backgroundColor = .black
        view.addSubview(videoView)
        NSLayoutConstraint.activate([
            videoView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            videoView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            videoView.topAnchor.constraint(equalTo: view.topAnchor),
            videoView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func configurePlaceholderView() {
        placeholderView.translatesAutoresizingMaskIntoConstraints = false
        placeholderView.backgroundColor = UIColor(white: 0.08, alpha: 1)
        view.addSubview(placeholderView)

        avatarImageView.translatesAutoresizingMaskIntoConstraints = false
        avatarImageView.contentMode = .scaleAspectFill
        avatarImageView.clipsToBounds = true
        avatarImageView.layer.cornerRadius = 28
        avatarImageView.backgroundColor = UIColor(white: 0.16, alpha: 1)

        initialsLabel.translatesAutoresizingMaskIntoConstraints = false
        initialsLabel.textAlignment = .center
        initialsLabel.textColor = UIColor.white.withAlphaComponent(0.88)
        initialsLabel.font = UIFont.systemFont(ofSize: 24, weight: .semibold)

        nameLabel.translatesAutoresizingMaskIntoConstraints = false
        nameLabel.textAlignment = .center
        nameLabel.textColor = UIColor.white.withAlphaComponent(0.86)
        nameLabel.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        nameLabel.numberOfLines = 1
        nameLabel.adjustsFontSizeToFitWidth = true
        nameLabel.minimumScaleFactor = 0.7

        placeholderView.addSubview(avatarImageView)
        placeholderView.addSubview(initialsLabel)
        placeholderView.addSubview(nameLabel)

        let avatarSizeConstraint = avatarImageView.widthAnchor.constraint(equalToConstant: 56)
        let avatarCenterYConstraint = avatarImageView.centerYAnchor.constraint(equalTo: placeholderView.centerYAnchor, constant: -10)
        let nameTopConstraint = nameLabel.topAnchor.constraint(equalTo: avatarImageView.bottomAnchor, constant: 10)
        self.avatarSizeConstraint = avatarSizeConstraint
        self.avatarCenterYConstraint = avatarCenterYConstraint
        self.nameTopConstraint = nameTopConstraint

        NSLayoutConstraint.activate([
            placeholderView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            placeholderView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            placeholderView.topAnchor.constraint(equalTo: view.topAnchor),
            placeholderView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            avatarImageView.centerXAnchor.constraint(equalTo: placeholderView.centerXAnchor),
            avatarCenterYConstraint,
            avatarSizeConstraint,
            avatarImageView.heightAnchor.constraint(equalTo: avatarImageView.widthAnchor),

            initialsLabel.centerXAnchor.constraint(equalTo: avatarImageView.centerXAnchor),
            initialsLabel.centerYAnchor.constraint(equalTo: avatarImageView.centerYAnchor),

            nameLabel.leadingAnchor.constraint(greaterThanOrEqualTo: placeholderView.leadingAnchor, constant: 18),
            nameLabel.trailingAnchor.constraint(lessThanOrEqualTo: placeholderView.trailingAnchor, constant: -18),
            nameLabel.centerXAnchor.constraint(equalTo: placeholderView.centerXAnchor),
            nameTopConstraint
        ])
    }

    private func updatePlaceholderLayoutMetrics() {
        let width = max(view.bounds.width, 1)
        let avatarSize = min(max(width * 0.36, 48), 140)
        let initialsSize = avatarSize * 0.41
        let nameSize = min(max(width * 0.087, 12), 34)
        let topSpacing = min(max(width * 0.046, 8), 18)

        avatarSizeConstraint?.constant = avatarSize
        avatarCenterYConstraint?.constant = -topSpacing
        nameTopConstraint?.constant = topSpacing
        avatarImageView.layer.cornerRadius = avatarSize / 2
        initialsLabel.font = UIFont.systemFont(ofSize: initialsSize, weight: .semibold)
        nameLabel.font = UIFont.systemFont(ofSize: nameSize, weight: .medium)
    }
}

private extension CallPhase {
    var isSystemPictureInPicturePhase: Bool {
        self == .waiting || self == .inCall
    }
}

#if canImport(WebRTC)
private final class SystemPictureInPictureVideoView: UIView, RTCVideoRenderer {
    override class var layerClass: AnyClass {
        AVSampleBufferDisplayLayer.self
    }

    private var sampleBufferDisplayLayer: AVSampleBufferDisplayLayer {
        layer as! AVSampleBufferDisplayLayer
    }

    private static let pixelBufferAttributes: CFDictionary = [
        kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        kCVPixelBufferCGImageCompatibilityKey as String: true,
        kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
    ] as CFDictionary

    private let renderQueue = DispatchQueue(label: "app.serenada.callui.system-pip-video")
    private let renderLock = NSLock()
    private let ciContext = CIContext()
    private var isRendering = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureLayer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configureLayer()
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard let frame else { return }
        renderLock.lock()
        if isRendering {
            renderLock.unlock()
            return
        }
        isRendering = true
        renderLock.unlock()

        renderQueue.async { [weak self] in
            guard let self else { return }
            let sampleBuffer = self.makeSampleBuffer(from: frame)
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                defer { self.finishRenderingFrame() }
                guard let sampleBuffer else { return }
                let layer = self.sampleBufferDisplayLayer
                if layer.status == .failed {
                    layer.flush()
                }
                if layer.isReadyForMoreMediaData {
                    layer.enqueue(sampleBuffer)
                }
            }
        }
    }

    private func configureLayer() {
        sampleBufferDisplayLayer.videoGravity = .resizeAspectFill
        sampleBufferDisplayLayer.backgroundColor = UIColor.black.cgColor
    }

    private func finishRenderingFrame() {
        renderLock.lock()
        isRendering = false
        renderLock.unlock()
    }

    private func makeSampleBuffer(from frame: RTCVideoFrame) -> CMSampleBuffer? {
        guard let pixelBuffer = normalizedPixelBuffer(from: frame) else { return nil }
        var formatDescription: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescriptionOut: &formatDescription
        ) == noErr, let formatDescription else {
            return nil
        }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: frame.timeStampNs, timescale: 1_000_000_000),
            decodeTimeStamp: .invalid
        )
        var sampleBuffer: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        ) == noErr, let sampleBuffer else {
            return nil
        }

        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let dictionary = unsafeBitCast(
                CFArrayGetValueAtIndex(attachments, 0),
                to: CFMutableDictionary.self
            )
            CFDictionarySetValue(
                dictionary,
                Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
            )
        }
        return sampleBuffer
    }

    private func normalizedPixelBuffer(from frame: RTCVideoFrame) -> CVPixelBuffer? {
        let sourcePixelBuffer: CVPixelBuffer?
        if let cv = frame.buffer as? RTCCVPixelBuffer {
            sourcePixelBuffer = cv.pixelBuffer
        } else {
            sourcePixelBuffer = i420ToCVPixelBuffer(frame.buffer.toI420())
        }
        guard let sourcePixelBuffer else { return nil }

        guard let orientation = cgOrientation(for: frame.rotation), orientation != .up else {
            return sourcePixelBuffer
        }

        var image = CIImage(cvPixelBuffer: sourcePixelBuffer).oriented(orientation)
        let extent = image.extent.integral
        guard extent.width > 0, extent.height > 0 else { return sourcePixelBuffer }
        image = image.transformed(by: CGAffineTransform(translationX: -extent.origin.x, y: -extent.origin.y))
        return render(image: image, width: Int(extent.width), height: Int(extent.height))
    }

    private func render(image: CIImage, width: Int, height: Int) -> CVPixelBuffer? {
        var output: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            Self.pixelBufferAttributes,
            &output
        )
        guard status == kCVReturnSuccess, let output else { return nil }
        ciContext.render(image, to: output)
        return output
    }
}

private func i420ToCVPixelBuffer(_ i420: RTCI420BufferProtocol) -> CVPixelBuffer? {
    let width = Int(i420.width)
    let height = Int(i420.height)
    guard width > 0, height > 0 else { return nil }
    var buffer: CVPixelBuffer?
    let attrs: [String: Any] = [kCVPixelBufferIOSurfacePropertiesKey as String: [:]]
    let status = CVPixelBufferCreate(
        kCFAllocatorDefault,
        width,
        height,
        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
        attrs as CFDictionary,
        &buffer
    )
    guard status == kCVReturnSuccess, let pb = buffer else { return nil }

    CVPixelBufferLockBaseAddress(pb, [])
    defer { CVPixelBufferUnlockBaseAddress(pb, []) }

    guard let yDst = CVPixelBufferGetBaseAddressOfPlane(pb, 0),
          let uvDst = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else {
        return nil
    }
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    let uvStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
    let yBytes = yDst.assumingMemoryBound(to: UInt8.self)
    let uvBytes = uvDst.assumingMemoryBound(to: UInt8.self)

    let srcYStride = Int(i420.strideY)
    for row in 0..<height {
        memcpy(
            yBytes.advanced(by: row * yStride),
            i420.dataY.advanced(by: row * srcYStride),
            width
        )
    }

    let chromaWidth = width / 2
    let chromaHeight = height / 2
    let strideU = Int(i420.strideU)
    let strideV = Int(i420.strideV)
    for row in 0..<chromaHeight {
        let srcURow = i420.dataU.advanced(by: row * strideU)
        let srcVRow = i420.dataV.advanced(by: row * strideV)
        let dstRow = uvBytes.advanced(by: row * uvStride)
        for x in 0..<chromaWidth {
            dstRow[x * 2] = srcURow[x]
            dstRow[x * 2 + 1] = srcVRow[x]
        }
    }

    return pb
}

private func cgOrientation(for rotation: RTCVideoRotation) -> CGImagePropertyOrientation? {
    switch Int(rotation.rawValue) {
    case 0: return .up
    case 90: return .right
    case 180: return .down
    case 270: return .left
    default: return nil
    }
}
#endif
