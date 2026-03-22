import Foundation

@MainActor
final class TurnManager {
    private let clock: SessionClock
    private let serverHost: String
    private let apiClient: SessionAPIClient
    private let getJoinAttemptSerial: () -> Int64
    private let getRoomId: () -> String
    private let getPhase: () -> SerenadaCallPhase
    private let isSignalingConnected: () -> Bool
    private let setIceServers: ([IceServerConfig]) -> Void
    private let onIceServersReady: () -> Void
    private let sendTurnRefresh: () -> Void

    private var turnRefreshTask: Task<Void, Never>?
    private var turnTokenTTLMs: Int64?
    private var hasInitializedIceSetupForAttempt = false
    private var lastTurnTokenForAttempt: String?

    init(
        clock: SessionClock,
        serverHost: String,
        apiClient: SessionAPIClient,
        getJoinAttemptSerial: @escaping () -> Int64,
        getRoomId: @escaping () -> String,
        getPhase: @escaping () -> SerenadaCallPhase,
        isSignalingConnected: @escaping () -> Bool,
        setIceServers: @escaping ([IceServerConfig]) -> Void,
        onIceServersReady: @escaping () -> Void,
        sendTurnRefresh: @escaping () -> Void
    ) {
        self.clock = clock
        self.serverHost = serverHost
        self.apiClient = apiClient
        self.getJoinAttemptSerial = getJoinAttemptSerial
        self.getRoomId = getRoomId
        self.getPhase = getPhase
        self.isSignalingConnected = isSignalingConnected
        self.setIceServers = setIceServers
        self.onIceServersReady = onIceServersReady
        self.sendTurnRefresh = sendTurnRefresh
    }

    func ensureIceSetupIfNeeded(turnToken: String?) {
        let normalizedToken = turnToken?.trimmingCharacters(in: .whitespacesAndNewlines)

        if !hasInitializedIceSetupForAttempt {
            hasInitializedIceSetupForAttempt = true
            applyDefaultIceServers()
        }

        guard let normalizedToken, !normalizedToken.isEmpty else { return }
        guard lastTurnTokenForAttempt != normalizedToken else { return }

        lastTurnTokenForAttempt = normalizedToken
        fetchTurnCredentials(token: normalizedToken, applyDefaultOnFailure: false)
    }

    func handleTurnRefreshed(payload: JSONValue?) {
        guard let payload = payload?.objectValue else { return }
        let phase = getPhase()
        guard phase != .idle else { return }
        if let ttl = payload["turnTokenTTLMs"]?.intValue {
            turnTokenTTLMs = Int64(ttl)
            scheduleTurnRefresh(ttlMs: Int64(ttl))
        }
        let turnToken = payload["turnToken"]?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        ensureIceSetupIfNeeded(turnToken: turnToken)
    }

    func handleJoinedTTL(ttlMs: Int64) {
        turnTokenTTLMs = ttlMs
        scheduleTurnRefresh(ttlMs: ttlMs)
    }

    func reset() {
        cancelRefresh()
        hasInitializedIceSetupForAttempt = false
        lastTurnTokenForAttempt = nil
        turnTokenTTLMs = nil
    }

    func cancelRefresh() {
        turnRefreshTask?.cancel()
        turnRefreshTask = nil
    }

    private func fetchTurnCredentials(token: String, applyDefaultOnFailure: Bool = true) {
        let roomIdAtFetchStart = getRoomId()
        let joinAttemptAtFetchStart = getJoinAttemptSerial()

        enum TurnFetchOutcome {
            case success(TurnCredentials)
            case failed
            case timedOut
        }

        Task {
            let outcome = await withTaskGroup(of: TurnFetchOutcome.self) { group in
                group.addTask { [apiClient, serverHost] in
                    do {
                        return .success(try await apiClient.fetchTurnCredentials(host: serverHost, token: token))
                    } catch {
                        return .failed
                    }
                }
                group.addTask { [clock] in
                    try? await clock.sleep(nanoseconds: WebRtcResilience.turnFetchTimeoutNs)
                    return .timedOut
                }
                let first = await group.next() ?? .failed
                group.cancelAll()
                return first
            }

            guard self.getRoomId() == roomIdAtFetchStart else { return }
            guard self.getJoinAttemptSerial() == joinAttemptAtFetchStart else { return }

            switch outcome {
            case .success(let credentials):
                self.applyTurnCredentials(credentials)
            case .timedOut, .failed:
                if applyDefaultOnFailure {
                    self.applyDefaultIceServers()
                }
            }
        }
    }

    private func applyTurnCredentials(_ credentials: TurnCredentials) {
        let servers = credentials.uris.map {
            IceServerConfig(urls: [$0], username: credentials.username, credential: credentials.password)
        }
        setIceServers(servers)
        onIceServersReady()
    }

    private func applyDefaultIceServers() {
        setIceServers([
            IceServerConfig(urls: ["stun:stun.l.google.com:19302"], username: nil, credential: nil)
        ])
        onIceServersReady()
    }

    private func scheduleTurnRefresh(ttlMs: Int64) {
        cancelRefresh()
        guard ttlMs > 0 else { return }
        let delayNs = UInt64(Double(ttlMs) * WebRtcResilience.turnRefreshTriggerRatio * 1_000_000)

        turnRefreshTask = Task { [weak self] in
            guard let clock = self?.clock else { return }
            try? await clock.sleep(nanoseconds: delayNs)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            let phase = self.getPhase()
            guard phase == .waiting || phase == .inCall || phase == .joining else { return }
            guard self.isSignalingConnected() else { return }
            self.sendTurnRefresh()
        }
    }
}
