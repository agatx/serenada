import Foundation

final class WebSocketSignalingTransport: NSObject, SignalingTransport {
    let kind: TransportKind = .ws

    private var session: URLSession?
    private var webSocket: URLSessionWebSocketTask?
    private var onOpenCallback: (() -> Void)?
    private var onMessageCallback: ((SignalingMessage) -> Void)?
    private var onClosedCallback: ((String) -> Void)?
    private var didClose = false

    func connect(
        host: String,
        onOpen: @escaping () -> Void,
        onMessage: @escaping (SignalingMessage) -> Void,
        onClosed: @escaping (String) -> Void
    ) {
        close()

        guard let url = buildWssURL(host: host) else {
            onClosed("invalid_host")
            return
        }

        onOpenCallback = onOpen
        onMessageCallback = onMessage
        onClosedCallback = onClosed
        didClose = false

        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session

        let task = session.webSocketTask(with: url)
        self.webSocket = task
        task.resume()
        receiveLoop()
    }

    func send(_ message: SignalingMessage) {
        guard let webSocket else { return }
        guard let raw = try? message.toJSONString() else { return }
        webSocket.send(.string(raw)) { [weak self] error in
            guard let self else { return }
            if let error {
                self.triggerClosed(reason: error.localizedDescription)
            }
        }
    }

    func close() {
        webSocket?.cancel(with: .goingAway, reason: nil)
        webSocket = nil
        session?.invalidateAndCancel()
        session = nil
        onOpenCallback = nil
        onMessageCallback = nil
        onClosedCallback = nil
        didClose = true
    }

    private func receiveLoop() {
        webSocket?.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text):
                    if let parsed = try? SignalingMessage.decode(from: text) {
                        self.onMessageCallback?(parsed)
                    }
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8),
                       let parsed = try? SignalingMessage.decode(from: text) {
                        self.onMessageCallback?(parsed)
                    }
                @unknown default:
                    break
                }
                self.receiveLoop()

            case .failure(let error):
                self.triggerClosed(reason: error.localizedDescription)
            }
        }
    }

    private func triggerClosed(reason: String) {
        if didClose { return }
        didClose = true
        onClosedCallback?(reason)
    }

    private func buildWssURL(host: String) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        var components = URLComponents()
        components.scheme = "wss"
        components.host = parsedHost.host
        components.port = parsedHost.port
        components.path = "/ws"
        return components.url
    }
}

extension WebSocketSignalingTransport: URLSessionWebSocketDelegate {
    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        onOpenCallback?()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        let reasonText: String
        if let reason, let text = String(data: reason, encoding: .utf8), !text.isEmpty {
            reasonText = text
        } else {
            reasonText = "close"
        }
        triggerClosed(reason: reasonText)
    }
}
