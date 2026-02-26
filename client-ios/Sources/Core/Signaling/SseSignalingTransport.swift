import Foundation

final class SseSignalingTransport: SignalingTransport {
    let kind: TransportKind = .sse

    private var sid = SseSignalingTransport.createSid()
    private var streamTask: Task<Void, Never>?
    private var currentHost: String?
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

        currentHost = host
        onMessageCallback = onMessage
        onClosedCallback = onClosed
        didClose = false

        guard let url = buildSseURL(host: host, sid: sid) else {
            onClosed("invalid_host")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 0

        streamTask = Task { [weak self] in
            guard let self else { return }

            do {
                let (bytes, response) = try await URLSession.shared.bytes(for: request)
                guard let http = response as? HTTPURLResponse else {
                    self.triggerClosed(reason: "invalid_response")
                    return
                }
                guard (200...299).contains(http.statusCode) else {
                    self.triggerClosed(reason: "http_\(http.statusCode)")
                    return
                }

                onOpen()

                var dataBuffer = String()
                for try await rawLine in bytes.lines {
                    let line = rawLine.replacingOccurrences(of: "\r", with: "")
                    if line.isEmpty {
                        self.dispatchMessage(dataBuffer: &dataBuffer)
                        continue
                    }
                    if line.hasPrefix(":") {
                        continue
                    }
                    guard line.hasPrefix("data:") else {
                        continue
                    }

                    var payload = String(line.dropFirst(5))
                    if payload.hasPrefix(" ") {
                        payload.removeFirst()
                    }

                    if !dataBuffer.isEmpty {
                        dataBuffer.append("\n")
                    }
                    dataBuffer.append(payload)
                }

                self.dispatchMessage(dataBuffer: &dataBuffer)
                self.triggerClosed(reason: "close")
            } catch {
                if Task.isCancelled { return }
                self.triggerClosed(reason: "failure")
            }
        }
    }

    func send(_ message: SignalingMessage) {
        guard let host = currentHost, let url = buildSseURL(host: host, sid: sid) else { return }
        guard let body = try? message.toJSONString().data(using: .utf8) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            guard let self else { return }
            if error != nil {
                return
            }
            if let http = response as? HTTPURLResponse, http.statusCode == 410 {
                self.triggerClosed(reason: "gone")
            }
        }.resume()
    }

    func close() {
        streamTask?.cancel()
        streamTask = nil
        currentHost = nil
        onMessageCallback = nil
        onClosedCallback = nil
        didClose = true
    }

    func resetSession() {
        sid = SseSignalingTransport.createSid()
        currentHost = nil
    }

    private func dispatchMessage(dataBuffer: inout String) {
        guard !dataBuffer.isEmpty else { return }
        let payload = dataBuffer
        dataBuffer.removeAll(keepingCapacity: true)

        guard let msg = try? SignalingMessage.decode(from: payload) else { return }
        onMessageCallback?(msg)
    }

    private func triggerClosed(reason: String) {
        if didClose { return }
        didClose = true
        onClosedCallback?(reason)
    }

    private func buildSseURL(host: String, sid: String) -> URL? {
        guard let parsedHost = EndpointHostParser.splitHostAndPort(from: host) else { return nil }

        var components = URLComponents()
        components.scheme = "https"
        components.host = parsedHost.host
        components.port = parsedHost.port
        components.path = "/sse"
        components.queryItems = [URLQueryItem(name: "sid", value: sid)]
        return components.url
    }

    private static func createSid() -> String {
        let bytes = (0..<8).map { _ in UInt8.random(in: .min ... .max) }
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        return "S-\(hex)"
    }
}
