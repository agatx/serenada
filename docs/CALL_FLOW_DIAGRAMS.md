# Call Setup & Recovery Flow Diagrams

Visual reference for the call lifecycle across all three Serenada client platforms.
All platforms share identical resilience constants and nearly identical state machines.

---

## Table of Contents

1. [Shared Resilience Constants](#1-shared-resilience-constants)
2. [Call Phase State Machine](#2-call-phase-state-machine)
3. [Web Client — Call Setup](#3-web-client--call-setup)
4. [Web Client — Call Recovery](#4-web-client--call-recovery)
5. [iOS Client — Call Setup](#5-ios-client--call-setup)
6. [iOS Client — Call Recovery](#6-ios-client--call-recovery)
7. [Android Client — Call Setup](#7-android-client--call-setup)
8. [Android Client — Call Recovery](#8-android-client--call-recovery)
9. [Platform Comparison](#9-platform-comparison)

---

## 1. Shared Resilience Constants

All three platforms define identical values in their `WebRtcResilienceConstants` files.
Cross-platform parity is enforced by `node scripts/check-resilience-constants.mjs`.

| Constant | Value | Category | Purpose |
|---|---|---|---|
| `RECONNECT_BACKOFF_BASE_MS` | 500 ms | Signaling | Exponential backoff base for reconnect |
| `RECONNECT_BACKOFF_CAP_MS` | 5,000 ms | Signaling | Maximum backoff interval |
| `CONNECT_TIMEOUT_MS` | 2,000 ms | Signaling | Transport connection timeout |
| `PING_INTERVAL_MS` | 12,000 ms | Signaling | Keep-alive ping frequency |
| `PONG_MISS_THRESHOLD` | 2 | Signaling | Consecutive missed pongs before force-close |
| `WS_FALLBACK_CONSECUTIVE_FAILURES` | 3 | Signaling | WS failures before SSE fallback allowed |
| `JOIN_PUSH_ENDPOINT_WAIT_MS` | 250 ms | Join | Max wait for push endpoint before sending join |
| `JOIN_CONNECT_KICKSTART_MS` | 1,200 ms | Join | Force signaling connect if not started |
| `JOIN_RECOVERY_MS` | 4,000 ms | Join | Re-send join or promote to Waiting |
| `JOIN_HARD_TIMEOUT_MS` | 15,000 ms | Join | Fail entire join attempt |
| `OFFER_TIMEOUT_MS` | 8,000 ms | Peer Connection | Wait for answer before rollback + ICE restart |
| `ICE_RESTART_COOLDOWN_MS` | 10,000 ms | Peer Connection | Minimum interval between ICE restarts |
| `NON_HOST_FALLBACK_DELAY_MS` | 4,000 ms | Peer Connection | Wait before non-host sends offer |
| `NON_HOST_FALLBACK_MAX_ATTEMPTS` | 2 | Peer Connection | Max non-host fallback offers |
| `ICE_CANDIDATE_BUFFER_MAX` | 50 | Peer Connection | Max queued ICE candidates before remote SDP |
| `TURN_FETCH_TIMEOUT_MS` | 2,000 ms | TURN | Credential fetch timeout |
| `TURN_REFRESH_TRIGGER_RATIO` | 0.8 | TURN | Refresh at 80% of TTL |
| `SNAPSHOT_PREPARE_TIMEOUT_MS` | 2,000 ms | Snapshot | Join snapshot preparation timeout |

**Source files:**
- Web: `client/src/constants/webrtcResilience.ts`
- iOS: `client-ios/Sources/Core/Call/WebRtcResilienceConstants.swift`
- Android: `client-android/.../call/WebRtcResilienceConstants.kt`

---

## 2. Call Phase State Machine

Shared across all three platforms. The phase drives UI state and determines which recovery mechanisms are active.

```mermaid
stateDiagram-v2
    [*] --> Idle

    Idle --> CreatingRoom : startNewCall()
    Idle --> Joining : joinRoom(roomId)

    CreatingRoom --> Joining : Room ID received
    CreatingRoom --> Error : API failure

    Joining --> Waiting : joined (1 participant)
    Joining --> InCall : joined (2 participants)
    Joining --> Error : Hard timeout (15s)

    Waiting --> InCall : room_state (2 participants)
    Waiting --> Ending : leaveCall()

    InCall --> Waiting : room_state (1 participant)
    InCall --> Ending : leaveCall() / endCall()
    InCall --> Ending : room_ended

    Ending --> Idle : cleanup complete

    Error --> Idle : dismissError()
```

**Phase descriptions:**

| Phase | Description |
|---|---|
| `Idle` | No active call. Home screen visible. |
| `CreatingRoom` | Requesting new room ID from server API. |
| `Joining` | Connecting signaling, sending `join`, awaiting `joined` ack. |
| `Waiting` | In room with 1 participant, waiting for peer. |
| `InCall` | 2 participants present, WebRTC media flowing. |
| `Ending` | Call teardown in progress. |
| `Error` | Join failed (timeout, server error, etc.). |

---

## 3. Web Client — Call Setup

**Key files:** `SignalingContext.tsx`, `WebRTCContext.tsx`, `CallRoom.tsx`

```mermaid
sequenceDiagram
    participant U as User
    participant CR as CallRoom
    participant SC as SignalingContext
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WC as WebRTCContext
    participant PC as PeerConnection

    Note over SC,T: Transport auto-connects on mount

    U->>CR: Navigate to /call/:roomId
    CR->>WC: startLocalMedia()
    WC->>WC: getUserMedia({video, audio})
    WC-->>CR: localStream ready

    CR->>CR: Prepare snapshot (≤2s timeout)
    CR->>SC: joinRoom(roomId, {snapshotId})

    alt Push endpoint available
        SC->>SC: Wait ≤250ms for push endpoint
        SC->>T: send({type: "join", payload: {pushEndpoint, reconnectCid, snapshotId}})
    else No push support
        SC->>T: send({type: "join", payload: {reconnectCid, snapshotId}})
    end

    Note over SC: Start join timers:<br/>Kickstart: 1.2s<br/>Recovery: 4s<br/>Hard timeout: 15s

    T->>S: join message
    S-->>T: joined {cid, hostCid, participants, turnToken, turnTokenTTLMs}
    T->>SC: handleIncomingMessage("joined")
    SC->>SC: Clear join timers
    SC->>SC: Store cid, roomState, turnToken

    SC->>WC: turnToken updated (via React state)
    WC->>S: GET /api/turn-credentials?token=... (≤2s timeout)
    S-->>WC: {username, password, uris, ttl}
    WC->>WC: setRtcConfig(TURN servers)

    alt Host (2 participants in room)
        Note over WC: roomState triggers offer
        WC->>PC: new RTCPeerConnection(rtcConfig)
        WC->>PC: addTrack(localStream tracks)
        WC->>PC: createOffer()
        PC-->>WC: offer SDP
        WC->>PC: setLocalDescription(offer)
        WC->>SC: sendMessage("offer", {sdp})
        Note over WC: Start offer timeout (8s)
        SC->>T: relay offer
        T->>S: offer → relay to peer
    else Non-host
        Note over WC: Wait for offer from host
        WC->>WC: Schedule non-host fallback (4s)
    end

    Note over S: Relay offer to peer

    S-->>T: offer from peer
    T->>SC: handleIncomingMessage("offer")
    SC->>WC: processSignalingMessage
    WC->>PC: setRemoteDescription(offer)
    WC->>PC: Flush buffered ICE candidates
    WC->>PC: createAnswer()
    PC-->>WC: answer SDP
    WC->>PC: setLocalDescription(answer)
    WC->>SC: sendMessage("answer", {sdp})

    par ICE candidate exchange
        PC->>WC: onicecandidate
        WC->>SC: sendMessage("ice", {candidate})
        SC->>T: relay ICE
        T->>S: ice → relay to peer
    end

    PC->>WC: oniceconnectionstatechange → "connected"
    Note over WC: Media flowing ✓
```

---

## 4. Web Client — Call Recovery

**Key files:** `SignalingContext.tsx` (reconnect), `WebRTCContext.tsx` (ICE restart)

```mermaid
sequenceDiagram
    participant Net as Browser Events
    participant SC as SignalingContext
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WC as WebRTCContext
    participant PC as PeerConnection

    Note over SC,PC: === Scenario 1: Signaling Disconnection ===

    T--xSC: Transport closed (network loss, server restart)
    SC->>SC: setIsConnected(false)
    SC->>SC: needsRejoin = true (if in room)

    alt WS never connected OR WS failed ≥3 times
        SC->>SC: shouldFallback() → true
        SC->>T: Try SSE transport
    else WS worked before
        SC->>SC: scheduleReconnect()
        Note over SC: Backoff: 500ms → 1s → 2s → 4s → 5s (cap)
        SC->>T: Reconnect same transport
    end

    T->>S: New connection
    T-->>SC: onOpen
    SC->>SC: Auto-rejoin room
    SC->>T: send("join", {reconnectCid})

    Note over SC,PC: === Scenario 2: ICE Disconnected (host) ===

    PC->>WC: iceConnectionState → "disconnected"
    WC->>WC: scheduleIceRestart("ice-disconnected", 2000ms)
    Note over WC: Wait 2s (transient recovery window)

    alt ICE recovers within 2s
        PC->>WC: iceConnectionState → "connected"
        WC->>WC: clearIceRestartTimer()
    else ICE stays disconnected
        WC->>WC: triggerIceRestart()
        Note over WC: Cooldown check: ≥10s since last restart
        WC->>PC: createOffer({iceRestart: true})
        PC-->>WC: offer SDP (new ICE credentials)
        WC->>SC: sendMessage("offer", {sdp})
        Note over WC: Start offer timeout (8s)
    end

    Note over SC,PC: === Scenario 3: ICE Failed (host) ===

    PC->>WC: iceConnectionState → "failed"
    WC->>WC: scheduleIceRestart("ice-failed", 0ms)
    WC->>WC: triggerIceRestart() — immediate
    WC->>PC: createOffer({iceRestart: true})

    Note over SC,PC: === Scenario 4: Network Recovery ===

    Net->>WC: window "online" event
    WC->>WC: scheduleIceRestart("network-online", 0ms)

    Net->>WC: navigator.connection "change" event
    alt ICE is disconnected or failed
        WC->>WC: scheduleIceRestart("network-change", 0ms)
    end

    Note over SC,PC: === Scenario 5: Offer Timeout (8s, host) ===

    Note over WC: 8s elapsed, no answer received
    WC->>WC: Offer timeout fires
    alt signalingState == "have-local-offer"
        WC->>PC: setLocalDescription({type: "rollback"})
        WC->>WC: scheduleIceRestart("offer-timeout", 0ms)
    else Unexpected state
        WC->>WC: scheduleIceRestart("offer-timeout-unexpected", 0ms)
    end

    Note over SC,PC: === Scenario 6: Non-Host Fallback (4s) ===

    Note over WC: Non-host: no offer received after 4s
    WC->>WC: nonHostFallbackTimer fires (attempt 1 of 2)
    alt No remote description & signaling stable
        WC->>PC: createOffer()
        WC->>SC: sendMessage("offer", {sdp})
    end
    Note over WC: If still no answer: retry once more (attempt 2)

    Note over SC,PC: === Scenario 7: Ping/Pong Timeout ===

    SC->>T: send("ping") every 12s
    Note over SC: No pong for 24s (2 missed pongs)
    SC->>T: transport.close()
    Note over SC: Triggers reconnect flow (Scenario 1)
```

---

## 5. iOS Client — Call Setup

**Key files:** `CallManager.swift`, `SignalingClient.swift`, `WebRtcEngine.swift`

```mermaid
sequenceDiagram
    participant U as User
    participant CM as CallManager
    participant SC as SignalingClient
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WE as WebRtcEngine
    participant PC as PeerConnection

    U->>CM: joinRoom(roomId)
    CM->>CM: phase = .joining
    CM->>CM: joinAttemptSerial++
    CM->>CM: recreateWebRtcEngine()

    Note over CM: Schedule timers:<br/>Hard timeout: 15s<br/>Kickstart: 1.2s

    CM->>CM: resolveMediaPermissions() async
    Note over CM: Request camera + mic<br/>(2s timeout per permission)

    CM->>CM: activateAudioSession()
    CM->>WE: startLocalMedia(preferVideo)
    CM->>WE: toggleAudio()/applyVideoPreference()

    CM->>CM: prepareJoinSnapshotAndConnect()
    CM->>CM: Prepare snapshot ID (≤2s)
    CM->>CM: ensureSignalingConnection()

    alt Already connected
        CM->>CM: sendJoin(roomId, snapshotId)
    else Not connected
        CM->>CM: pendingJoinRoom = roomId
        CM->>SC: connect(host)
        SC->>T: WS connect (2s timeout)
        T->>S: WebSocket handshake
        S-->>T: Connected
        T-->>SC: onOpen
        SC-->>CM: onOpen(transport: "ws")
        CM->>CM: reconnectAttempts = 0
        CM->>CM: sendJoin(pendingJoinRoom)
    end

    CM->>CM: fetchPushEndpoint (≤250ms)
    CM->>SC: send({type: "join", rid, payload: {device: "ios", reconnectCid, pushEndpoint, snapshotId}})

    Note over CM: Schedule join recovery (4s)

    SC->>T: join message
    T->>S: join
    S-->>T: joined {cid, hostCid, participants, turnToken, turnTokenTTLMs}
    T-->>SC: onMessage
    SC-->>CM: onMessage("joined")

    CM->>CM: Clear join timers
    CM->>CM: Store cid, reconnectToken
    CM->>CM: scheduleTurnRefresh(ttlMs × 0.8)

    CM->>CM: ensureIceSetupIfNeeded()
    CM->>WE: setIceServers(default STUN)
    CM->>S: GET /api/turn-credentials?token=... (≤2s)
    S-->>CM: {username, password, uris}
    CM->>WE: setIceServers(TURN)
    CM->>CM: flushPendingMessages()

    CM->>CM: updateParticipants(roomState)

    alt Host (2 participants)
        CM->>CM: phase = .inCall
        CM->>WE: ensurePeerConnection()
        CM->>CM: maybeSendOffer()
        CM->>WE: createOffer()
        WE->>PC: createOffer
        PC-->>WE: offer SDP
        WE-->>CM: onSdp(offer)
        CM->>SC: send("offer", {sdp})
        Note over CM: scheduleOfferTimeout(8s)
    else Non-host (2 participants)
        CM->>CM: phase = .inCall
        CM->>WE: ensurePeerConnection()
        CM->>CM: scheduleNonHostFallback(4s)
        Note over CM: Wait for host offer
    else 1 participant
        CM->>CM: phase = .waiting
    end

    Note over S: Relay offer/answer to peer

    S-->>T: offer from peer
    T-->>SC: onMessage("offer")
    SC-->>CM: onMessage
    CM->>WE: setRemoteDescription(offer)
    CM->>WE: createAnswer()
    WE-->>CM: onSdp(answer)
    CM->>SC: send("answer", {sdp})

    par ICE candidate exchange
        WE-->>CM: onLocalIceCandidate
        CM->>SC: send("ice", {candidate})
    end

    WE-->>CM: onConnectionState("CONNECTED")
    Note over CM: Media flowing ✓
```

---

## 6. iOS Client — Call Recovery

**Key files:** `CallManager.swift` (NWPathMonitor, ICE restart, reconnect), `SignalingClient.swift`

```mermaid
sequenceDiagram
    participant NW as NWPathMonitor
    participant CM as CallManager
    participant SC as SignalingClient
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WE as WebRtcEngine
    participant PC as PeerConnection

    Note over CM,PC: === Scenario 1: Signaling Disconnection ===

    T--xSC: Transport closed
    SC-->>CM: onClosed(reason)
    CM->>CM: isSignalingConnected = false
    CM->>CM: isReconnecting = shouldReconnect()

    alt WS unsupported/timeout OR never connected OR ≥3 failures
        SC->>SC: shouldFallback() → true
        SC->>T: Try SSE transport (2s timeout)
    else Normal close
        SC-->>CM: onClosed
        CM->>CM: scheduleReconnect()
        Note over CM: Backoff: 500ms → 1s → 2s → 4s → 5s (cap)
        CM->>SC: connect(host)
    end

    SC-->>CM: onOpen(transport)
    CM->>CM: reconnectAttempts = 0
    CM->>CM: Send pendingJoinRoom (auto-rejoin)
    alt Pending ICE restart
        CM->>CM: triggerIceRestart("signaling-reconnect")
    end

    Note over CM,PC: === Scenario 2: ICE Disconnected ===

    WE-->>CM: onConnectionState("DISCONNECTED")
    CM->>CM: scheduleIceRestart("conn-disconnected", 2000ms)
    WE-->>CM: onIceConnectionState("DISCONNECTED")
    CM->>CM: scheduleIceRestart("ice-disconnected", 2000ms)

    alt Recovers within 2s
        WE-->>CM: onConnectionState("CONNECTED")
        CM->>CM: clearIceRestartTimer()
    else Still disconnected (host)
        CM->>CM: triggerIceRestart()
        Note over CM: Cooldown: ≥10s since last
        CM->>WE: createOffer(iceRestart: true)
        WE->>PC: createOffer({iceRestart: true})
        PC-->>WE: offer SDP
        WE-->>CM: onSdp
        CM->>SC: send("offer", {sdp})
    end

    Note over CM,PC: === Scenario 3: ICE Failed ===

    WE-->>CM: onIceConnectionState("FAILED")
    CM->>CM: scheduleIceRestart("ice-failed", 0ms)
    CM->>CM: triggerIceRestart() — immediate

    Note over CM,PC: === Scenario 4: Network Recovery ===

    NW->>CM: pathUpdateHandler (path.status == .satisfied)
    alt phase == .inCall
        CM->>CM: scheduleIceRestart("network-online", 0ms)
    end

    Note over CM,PC: === Scenario 5: Offer Timeout (8s) ===

    Note over CM: 8s, no answer received
    alt signalingState == "HAVE_LOCAL_OFFER"
        CM->>WE: rollbackLocalDescription()
        CM->>CM: scheduleIceRestart("offer-timeout", 0ms)
    end

    Note over CM,PC: === Scenario 6: Non-Host Fallback (4s) ===

    Note over CM: Non-host: no offer received after 4s
    CM->>CM: nonHostOfferFallbackTask fires (attempt 1 of 2)
    alt No remote description & stable & not making offer
        CM->>WE: createOffer()
        WE-->>CM: onSdp
        CM->>SC: send("offer", {sdp})
        CM->>CM: scheduleOfferTimeout(8s, triggerIceRestart: false)
    end
    Note over CM: Retry once more if no answer (attempt 2)

    Note over CM,PC: === Scenario 7: Ping/Pong Timeout ===

    SC->>T: send("ping") every 12s
    Note over SC: No pong for 24s (2 × 12s intervals)
    SC->>SC: handleTransportClosed("pong_timeout")
    SC-->>CM: onClosed("pong_timeout")
    Note over CM: Triggers reconnect (Scenario 1)

    Note over CM,PC: === Scenario 8: Join Recovery (4s) ===

    Note over CM: 4s after sendJoin, joinRecoveryTask fires
    alt No joined ack yet
        CM->>CM: pendingJoinRoom = roomId
        CM->>CM: ensureSignalingConnection()
        Note over CM: Re-sends join
    else Ack received but still in Joining phase
        CM->>CM: recoverFromJoiningIfNeeded()
        CM->>CM: phase → Waiting or InCall
    end
```

---

## 7. Android Client — Call Setup

**Key files:** `CallManager.kt`, `SignalingClient.kt`, `WebRtcEngine.kt`

```mermaid
sequenceDiagram
    participant U as User
    participant CM as CallManager
    participant SC as SignalingClient
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WE as WebRtcEngine
    participant PC as PeerConnection

    U->>CM: joinRoom(roomId)
    CM->>CM: phase = Joining
    CM->>CM: joinAttemptSerial++
    CM->>CM: recreateWebRtcEngine()

    Note over CM: Schedule timers:<br/>Hard timeout: 15s<br/>Kickstart: 1.2s

    CM->>CM: acquirePerformanceLocks()
    Note over CM: CPU wake lock + WiFi lock
    CM->>CM: activateAudioSession()
    CM->>WE: startLocalMedia()
    CM->>WE: toggleAudio() / applyVideoPreference()

    CM->>CM: startRemoteVideoStatePolling()
    CM->>CM: prepareJoinSnapshotAndConnect()
    CM->>CM: Prepare snapshot ID (≤2s)
    CM->>CM: ensureSignalingConnection()

    alt Already connected
        CM->>CM: sendJoin(roomId, snapshotId)
    else Not connected
        CM->>CM: pendingJoinRoom = roomId
        CM->>SC: connect(host)
        SC->>T: WS connect (2s timeout)
        T->>S: WebSocket handshake
        S-->>T: Connected
        T-->>SC: onOpen
        SC-->>CM: onOpen(transport: "ws")
        CM->>CM: reconnectAttempts = 0
        CM->>CM: sendJoin(pendingJoinRoom)
    end

    CM->>CM: Wait ≤250ms for FCM token
    CM->>SC: send({type: "join", rid, payload: {device: "android", reconnectCid, pushEndpoint, snapshotId}})

    Note over CM: Schedule join recovery (4s)

    SC->>T: join message
    T->>S: join
    S-->>T: joined {cid, hostCid, participants, turnToken, turnTokenTTLMs}
    T-->>SC: onMessage
    SC-->>CM: onMessage("joined")

    CM->>CM: Clear join timers
    CM->>CM: Store cid, reconnectToken
    CM->>CM: scheduleTurnRefresh(ttlMs × 0.8)

    CM->>WE: setIceServers(default STUN)
    CM->>S: GET /api/turn-credentials?token=... (≤2s)
    S-->>CM: {username, password, uris}
    CM->>WE: setIceServers(TURN)
    CM->>CM: flushPendingMessages()

    CM->>CM: updateParticipants(roomState)

    alt Host (2 participants)
        CM->>CM: phase = InCall
        CM->>WE: ensurePeerConnection()
        CM->>CM: maybeSendOffer()
        CM->>WE: createOffer()
        WE->>PC: createOffer
        PC-->>WE: offer SDP
        WE-->>CM: onSdp(offer)
        CM->>SC: send("offer", {sdp})
        Note over CM: scheduleOfferTimeout(8s)
    else Non-host (2 participants)
        CM->>CM: phase = InCall
        CM->>WE: ensurePeerConnection()
        CM->>CM: scheduleNonHostFallback(4s)
        Note over CM: Wait for host offer
    else 1 participant
        CM->>CM: phase = Waiting
    end

    Note over S: Relay offer/answer to peer

    S-->>T: offer from peer
    T-->>SC: onMessage("offer")
    SC-->>CM: onMessage
    CM->>WE: setRemoteDescription(offer)
    CM->>WE: createAnswer()
    WE-->>CM: onSdp(answer)
    CM->>SC: send("answer", {sdp})

    par ICE candidate exchange
        WE-->>CM: onLocalIceCandidate
        CM->>SC: send("ice", {candidate})
    end

    WE-->>CM: onConnectionState(CONNECTED)
    Note over CM: Media flowing ✓

    CM->>CM: CallService.start() (foreground notification)
```

---

## 8. Android Client — Call Recovery

**Key files:** `CallManager.kt` (ConnectivityManager, ICE restart, reconnect), `SignalingClient.kt`

```mermaid
sequenceDiagram
    participant Net as ConnectivityManager
    participant CM as CallManager
    participant SC as SignalingClient
    participant T as Transport (WS/SSE)
    participant S as Server
    participant WE as WebRtcEngine
    participant PC as PeerConnection

    Note over CM,PC: === Scenario 1: Signaling Disconnection ===

    T--xSC: Transport closed
    SC-->>CM: onClosed(reason)
    CM->>CM: isSignalingConnected = false
    CM->>CM: isReconnecting = shouldReconnect()

    alt WS unsupported/timeout OR never connected OR ≥3 failures
        SC->>SC: shouldFallback() → true
        SC->>T: Try SSE transport (2s timeout)
    else Normal close
        SC-->>CM: onClosed
        CM->>CM: scheduleReconnect()
        Note over CM: Backoff: 500ms → 1s → 2s → 4s → 5s (cap)
        CM->>SC: connect(host)
    end

    SC-->>CM: onOpen(transport)
    CM->>CM: reconnectAttempts = 0
    CM->>CM: Send pendingJoinRoom (auto-rejoin)
    alt Pending ICE restart
        CM->>CM: triggerIceRestart("signaling-reconnect")
    end

    Note over CM,PC: === Scenario 2: ICE Disconnected ===

    WE-->>CM: onConnectionState(DISCONNECTED)
    CM->>CM: scheduleIceRestart("conn-disconnected", 2000ms)
    WE-->>CM: onIceConnectionState(DISCONNECTED)
    CM->>CM: scheduleIceRestart("ice-disconnected", 2000ms)

    alt Recovers within 2s
        WE-->>CM: onConnectionState(CONNECTED)
        CM->>CM: clearIceRestartTimer()
    else Still disconnected (host)
        CM->>CM: triggerIceRestart()
        Note over CM: Cooldown: ≥10s since last
        CM->>WE: createOffer(iceRestart = true)
        WE->>PC: createOffer({iceRestart: true})
        PC-->>WE: offer SDP
        WE-->>CM: onSdp
        CM->>SC: send("offer", {sdp})
    end

    Note over CM,PC: === Scenario 3: ICE Failed ===

    WE-->>CM: onIceConnectionState(FAILED)
    CM->>CM: scheduleIceRestart("ice-failed", 0ms)
    CM->>CM: triggerIceRestart() — immediate

    Note over CM,PC: === Scenario 4: Network Recovery ===

    Net->>CM: NetworkCallback.onAvailable()
    alt phase == InCall
        CM->>CM: scheduleIceRestart("network-online", 0ms)
    end

    Note over CM,PC: === Scenario 5: Offer Timeout (8s) ===

    Note over CM: 8s, no answer received
    alt signalingState == HAVE_LOCAL_OFFER
        CM->>WE: rollbackLocalDescription()
        CM->>CM: scheduleIceRestart("offer-timeout", 0ms)
    else Stale state
        CM->>CM: scheduleIceRestart("offer-timeout-stale", 0ms)
    end

    Note over CM,PC: === Scenario 6: Non-Host Fallback (4s) ===

    Note over CM: Non-host: no offer received after 4s
    CM->>CM: nonHostOfferFallback fires (attempt 1 of 2)
    alt No remote description & stable & not making offer
        CM->>WE: createOffer()
        WE-->>CM: onSdp
        CM->>SC: send("offer", {sdp})
        CM->>CM: scheduleOfferTimeout(8s)
    end
    Note over CM: Retry once more if no answer (attempt 2)

    Note over CM,PC: === Scenario 7: Ping/Pong Timeout ===

    SC->>T: send("ping") every 12s
    Note over SC: No pong for 24s (2 × 12s intervals)
    SC->>SC: handleTransportClosed("pong_timeout")
    SC-->>CM: onClosed("pong_timeout")
    Note over CM: Triggers reconnect (Scenario 1)

    Note over CM,PC: === Scenario 8: Join Recovery (4s) ===

    Note over CM: 4s after sendJoin, joinRecovery fires
    alt No joined ack yet
        CM->>CM: pendingJoinRoom = roomId
        CM->>CM: ensureSignalingConnection()
        Note over CM: Re-sends join
    else Ack received but still Joining
        CM->>CM: Promote to Waiting phase
    end
```

---

## 9. Platform Comparison

### Architecture Mapping

| Concept | Web (React) | iOS (SwiftUI) | Android (Compose) |
|---|---|---|---|
| Call orchestrator | `SignalingContext` + `WebRTCContext` | `CallManager` | `CallManager` |
| Signaling client | `SignalingContext` (built-in) | `SignalingClient` | `SignalingClient` |
| WebRTC wrapper | `WebRTCContext` (browser API) | `WebRtcEngine` | `WebRtcEngine` |
| State management | React Context + useState | `@Published` + `ObservableObject` | `StateFlow` + `MutableStateFlow` |
| Threading | Single-threaded (event loop) | `@MainActor` + Swift Tasks | `Handler(MainLooper)` + coroutines |
| Network monitoring | `navigator.onLine` + `navigator.connection` | `NWPathMonitor` | `ConnectivityManager.NetworkCallback` |
| Audio session | Browser-managed | `AVAudioSession` (manual) | `AudioManager` (manual) |
| Wake lock | N/A (browser manages) | `isIdleTimerDisabled` | CPU + WiFi wake locks |
| Push token | Web Push endpoint | FCM token | FCM token |
| Camera modes | front/back (binary) | selfie/world/composite (3-mode) | selfie/world/composite (3-mode) |

### Transport Fallback Flow (all platforms)

```mermaid
flowchart TD
    A[Connect WS] -->|Success| B[Connected ✓]
    A -->|Timeout 2s| C{Fallback?}
    A -->|Unsupported| C
    A -->|Never connected| C
    A -->|≥3 consecutive failures| C

    C -->|Yes & SSE available| D[Connect SSE]
    C -->|No / already on SSE| E[Exponential Backoff]

    D -->|Success| B
    D -->|Failure| E

    E -->|500ms → 1s → 2s → 4s → 5s cap| A

    B -->|Ping every 12s| F{Pong received?}
    F -->|Yes| B
    F -->|2 missed pongs| G[Force close transport]
    G --> C
```

### Recovery Mechanism Summary

| Mechanism | Trigger | Delay | Action | Max Attempts |
|---|---|---|---|---|
| Join kickstart | Signaling not started | 1.2s | `ensureSignalingConnection()` | 1 |
| Join recovery | No `joined` ack | 4s | Re-send `join` or promote phase | 1 |
| Join hard timeout | Still in Joining phase | 15s | Fail to Error phase | 1 |
| Signaling reconnect | Transport closed | 500ms–5s (exp. backoff) | Reconnect + auto-rejoin | Unlimited |
| WS → SSE fallback | WS fails | Immediate | Try SSE transport | 1 |
| ICE restart (disconnected) | ICE/conn disconnected | 2s | Offer with `iceRestart: true` | Unlimited (10s cooldown) |
| ICE restart (failed) | ICE/conn failed | 0s | Offer with `iceRestart: true` | Unlimited (10s cooldown) |
| ICE restart (network) | Network recovered | 0s | Offer with `iceRestart: true` | Unlimited (10s cooldown) |
| Offer timeout | No answer received | 8s | Rollback SDP + ICE restart | Unlimited |
| Non-host fallback | No offer from host | 4s | Non-host sends offer | 2 |
| Ping/pong timeout | 2 missed pongs | 24s (2 × 12s) | Force close transport | N/A |
| TURN refresh | Approaching TTL expiry | TTL × 0.8 | Send `turn-refresh` | Unlimited |
