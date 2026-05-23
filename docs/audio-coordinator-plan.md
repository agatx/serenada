# Audio Coordinator Design

Status: Draft (revision 3, mute composition contract + leading-edge leak acceptance)
Last updated: 2026-05-22

## Problem

The Serenada mobile SDKs (iOS, Android) currently manage their own audio sessions. They hardcode AVAudioSession category/mode/options (iOS) and AudioManager mode + audio focus + SCO routing (Android). This works fine when the SDK is the only audio-aware library in the host app.

When the SDK is embedded in an app that also processes audio and has its own audio session logic (canonical example: a push-to-talk app like Zello), the two systems fight over process-global state. We need an architecture that lets the SDK keep its in-call routing UX (speaker / earpiece / Bluetooth selector, sensible defaults, proximity-based earpiece switching) while letting a sophisticated host plug its own audio policy underneath.

A second realization shifts the design: on both iOS and Android, a Serenada video call and a PTT call **can coexist with full media** during the PTT moment itself. The primary collisions are not "two sessions fighting" but mic leak (outgoing PTT audio bleeding into the call's mic capture), acoustic mixing (incoming PTT audio overlapping call playback), and a brief iOS audio dropout (~100-200ms) when the PTT framework deactivates the shared AVAudioSession at PTT end. This shifts the SDK's primary lifecycle behavior from "suspend or terminate" to "mute the mic and duck playback during PTT, then resume cleanly."

## Current State

### Serenada iOS

`CallAudioSessionController.swift:43-72, 145, 162`:
- Category: `.playAndRecord` with mode `.voiceChat` and options `[.allowBluetooth, .allowBluetoothA2DP, .mixWithOthers]`
- Calls `setActive(true)` on join, `setActive(false, .notifyOthersOnDeactivation)` on leave
- Calls `overrideOutputAudioPort(.speaker)` / `.none` based on proximity and Bluetooth state
- Owns `AVAudioSession.routeChangeNotification` listening

`WebRtcEngine.swift` builds audio tracks and peer connections but has zero `RTCAudioSession` integration. The SDK pokes `AVAudioSession` directly, which means WebRTC's internal audio device module is not coordinated with category, route, or activation changes. This is a latent integration bug.

`SerenadaSession.swift:312-315` injects the audio controller internally. The public API exposes only `SerenadaConfig.proximityMonitoringEnabled`. No way for the host to provide its own audio implementation.

No CallKit or PushToTalk framework usage.

### Serenada Android

`CallAudioSessionController.kt:74-244, 318-344`:
- Sets `AudioManager.mode = MODE_IN_COMMUNICATION` on join
- Requests exclusive `AUDIOFOCUS_GAIN` with `USAGE_VOICE_COMMUNICATION`
- Forces `isMicrophoneMute = false`
- Drives `setCommunicationDevice` (Android 12+) or `startBluetoothSco` (legacy)
- Restores prior mode on deactivate

`WebRtcEngine.kt:137-195` builds `JavaAudioDeviceModule` with `VOICE_COMMUNICATION` source and hardware AEC/NS. `SessionMediaEngine` exposes only `toggleAudio` (which maps to `AudioTrack.setEnabled`), so there is no capture suspension primitive separate from sender-track toggling.

Public API exposes zero audio knobs.

### Zello iOS (reference)

- Three-state model (inactive → playing → recording), categories only (never modes)
- All state changes serialized through `AudioConcurrency` queue
- Uses Apple's PushToTalk framework on iOS 16+. When framework is active, Zello's `AudioSessionManager` skips `setActive(false)`.
- Pre-iOS-16 used CallKit, now disabled
- Aggressive route-change handling via `AudioSessionRouteChangeParser`

### Zello Android (reference)

- Never calls `AudioManager.setMode()`. Reads it only to detect cellular calls.
- Transient audio focus with ducking
- `acquireAudioDevice()` / `releaseAudioDevice()` counter pattern for shared access
- Owns Bluetooth SCO state machine (Android 12+ `setCommunicationDevice` > `startVoiceRecognition` > legacy `startBluetoothSco`)
- Device-specific workarounds (Zebra TC52 volume preservation, JP45 keep-alive)

## iOS PTT Coexistence (the load-bearing detail)

This section grounds the design. On iOS 16+, `AVAudioSession` is process-shared, not process-exclusive: multiple audio I/O paths (WebRTC's `RTCAudioSession`-backed ADM, PTT framework's RemoteIO, AVAudioEngine consumers) can live on top of one active session. During a PTT moment, while the framework holds the session active with `.playAndRecord` + a voice-style mode, both Serenada and PTT pipelines produce/consume audio. The output bus mixes; the input bus can be tapped by multiple consumers.

The collisions are these, not "two sessions exist":

1. **Outgoing PTT mic leak.** When the user keys PTT, their voice goes into both PTT's transmission path and Serenada's audio sender. The remote video caller hears the PTT chatter. Solution: SDK mutes its own audio sender for the PTT duration via `track.setEnabled(false)`. WebRTC sender stays alive; only the track is disabled. This is the **default coexistence behavior** in the revised design.

2. **Incoming PTT audio mix.** Remote PTT audio plays on speaker, Serenada's incoming audio also plays on speaker, user hears both layered. Tolerable for brief PTT, distracting for longer. Solution: optional ducking of Serenada's remote audio track during PTT.

3. **Deactivation gap at PTT end.** When PTT ends, the framework calls `setActive(false, .notifyOthersOnDeactivation)` on the shared session. All audio I/O units in the process get torn down for ~100-200ms before the host re-activates. Audible as a brief dropout. Bounded, recoverable.

4. **Route forcing.** PTT typically forces speakerphone for radio ergonomics. If Serenada was on Bluetooth, the route flips. Coordinator must save pre-PTT route and restore on resume.

On Android, both pipelines can run with full media on most devices. Capture is occasionally serialized on older / budget devices, where `AudioRecord.startRecording()` errors if another process holds the input. This is the only case where SDK genuinely loses its mic for the PTT duration, and it needs a real capture suspend/resume primitive.

### Leading-Edge Audio Leak (accepted)

Mute-during-PTT is best-effort, not synchronous. There is typically a ~50-100ms window at each edge of the PTT moment where audio leaks:

- **Start edge**: PTT audio flows through the framework's pipeline before `audioSessionInterrupted` reaches the SDK and the mute applies. The remote Serenada caller hears a brief slice of PTT audio bleed-through.
- **End edge**: The mute persists until `audioSessionResumed`, but the iOS deactivation gap may swallow tail audio anyway. Bounded.

This applies to both outgoing PTT (host could in theory pre-notify before `requestBeginTransmitting`, but we accept the simpler post-hoc flow) and incoming PTT (OS-initiated; pre-notification is not possible by construction). The complexity of a synchronous handshake to close this window is not justified by the tiny duration involved.

Hosts that require zero leak should publish `pttPolicy: .block` (no PTT while a call is active) instead of attempting tighter coexistence semantics. The SDK does not promise stricter-than-best-effort behavior on the coexist path.

## Goals and Non-Goals

### Goals

1. SDK keeps its full call-UI surface: device list, current device, user selection, default ranking, proximity earpiece, route-change reaction
2. Apps with their own audio policy can plug it in without losing SDK UX
3. Standalone behavior unchanged. Default config produces today's behavior.
4. No duplication of complex audio infrastructure (e.g., Zello's Bluetooth state machine) in the SDK
5. Serenada video call and PTT call coexist with full media by default. The SDK's primary cross-app behavior is "mute mic and duck playback during PTT," not "suspend or terminate the call."
6. Fix the latent `RTCAudioSession` bypass on iOS as part of the work

### Non-Goals

1. CallKit integration inside the SDK (host's job)
2. PushToTalk framework integration inside the SDK (host's job)
3. Owning the activation/deactivation of the shared `AVAudioSession` when a host coordinator is supplied
4. Supporting more than one host coordinator per session

## Design

### Ownership Split

**SDK owns** (never delegates):

- Routing policy: wired > Bluetooth > speaker > earpiece, with earpiece in the pool only when video is off or proximity is near. Policy consumes coordinator-published facts (device direction, BT profile, status) rather than enumerating devices itself.
- User selection memory ("user picked speaker, do not auto-flip back to BT on a route refresh")
- Proximity sensor handling
- WebRTC audio I/O lifecycle: `RTCAudioSession.useManualAudio = true` + `isAudioEnabled` gating on iOS; `JavaAudioDeviceModule` start/stop sequencing on Android. Always SDK-controlled, never delegated.
- Reacting to coordinator events (mute mic, duck playback, suspend capture, terminate)
- Public APIs the host's call UI binds to

**Coordinator owns**:

- `AVAudioSession` category, mode, options, activation (iOS)
- `AudioManager` mode, focus, communication device, SCO state machine (Android)
- Bluetooth profile state machine
- System device enumeration **and** route-change subscription. SDK does not subscribe to `AVAudioSession.routeChangeNotification` or `AudioDeviceCallback` directly. The coordinator owns the OS-level subscription and emits structured events to the SDK.
- Reporting external events: route changes, interruptions, focus loss
- Publishing static capabilities once on call activation (PTT policy, input-sharing support, session-ownership model)

The SDK uses an internal default coordinator that does exactly what `CallAudioSessionController` does today. Most apps use it by leaving `audioCoordinator` unset. Behavior unchanged.

### Data Model

The original draft used a flat `AudioDevice` enum. Codex review correctly flagged this as too weak. The revised model carries the facts the SDK's routing policy actually needs.

```
AudioDevice {
  id: String                          // stable, hashable; survives reconnect
  displayName: String
  kind: AudioDeviceKind
  direction: AudioDeviceDirection     // .input | .output | .both
  status: AudioDeviceStatus           // .available | .connecting | .active
}

AudioDeviceKind:
  - wiredHeadset
  - bluetooth(profile: BluetoothProfile)
  - speakerphone
  - earpiece
  - carAudio
  - usb
  - other

BluetoothProfile: .hfp | .a2dp | .ble | .unknown
AudioDeviceDirection: .input | .output | .both
AudioDeviceStatus: .available | .connecting | .active
```

The profile matters: an A2DP-only BT device cannot carry the call's mic, so the SDK ranking must exclude it from input-routing decisions. The status matters: a "connecting" BT device should not be auto-selected over an active wired headset.

```
AudioIntent {
  supportsVideo: Bool                              // affects earpiece pool
  requiresCapture: Bool                            // default true
  requiresPlayback: Bool                           // default true
  preferredDevice: AudioDevice?
  enableProximityEarpiece: Bool                    // default true
  muteOwnMicDuringExternalAudio: Bool              // default true. SDK mutes its mic
                                                   // when the coordinator reports an
                                                   // interruption (e.g., PTT active).
  duckOwnPlaybackDuringExternalAudio: Bool         // default true. SDK lowers its
                                                   // remote audio track volume during
                                                   // an interruption.
  exclusiveSession: Bool                           // default false. When true, SDK
                                                   // asks the coordinator to deny
                                                   // concurrent audio (rarely used).
}
```

The two `*DuringExternalAudio` knobs are the primary policy levers for PTT coexistence. The defaults match the recommended UX (mute mic to prevent leak; duck playback so the user can hear PTT). Hosts that want different behavior (e.g., don't duck because their PTT volume is already low, or don't mute because the PTT audio is acoustically separated via headset) flip the flags.

Events split into facts (what happened) and capabilities (what the host promises about the environment). This is the structural fix Codex pushed for.

```
AudioCoordinatorEvent (facts, coordinator → SDK):
  - availableDevicesChanged([AudioDevice])
  - effectiveRouteChanged(input: AudioDevice?, output: AudioDevice?)
  - audioSessionInterrupted(reason: InterruptionReason)
  - audioSessionResumed
  - inputUnavailable                          // mic gone (permission, hardware, exclusive lock)
  - inputAvailable
  - focusLost(transient: Bool)                // Android-flavored; iOS folds into interruption
  - focusRegained
```

```
AudioCoordinatorCapabilities (published once on activate):
  pttPolicy: PttPolicy
  canShareInput: Bool                         // false on Android devices with serialized AudioRecord
  sessionOwnership: SessionOwnership
  supportedDeviceKinds: [AudioDeviceKind]

PttPolicy:
  - coexist          // PTT and call run concurrently; SDK mutes/ducks per intent
  - preempt          // PTT terminates the call
  - block            // host promises PTT will not fire while a call is active

SessionOwnership:
  - sdkOwned         // SDK internal default coordinator drives activation
  - hostOwned        // coordinator owns activation (e.g., Zello AudioSessionManager)
  - systemOwned      // OS framework owns activation (e.g., iOS PushToTalk while channel active)

InterruptionReason: phoneCall | otherAudio | pttTransmission | systemAudio | unknown
```

The SDK consumes facts and capabilities and decides actions internally. The coordinator no longer prescribes "suspend everything" or "mute mic only." The coordinator says "PTT is starting." The SDK consults `pttPolicy` and `muteOwnMicDuringExternalAudio` and acts.

### Swift Protocol

```swift
public protocol SerenadaAudioCoordinator: AnyObject, Sendable {
    func activateCallSession(intent: AudioIntent) async throws -> AudioCoordinatorCapabilities
    func deactivateCallSession() async
    func applyRouting(_ device: AudioDevice) async throws
    func setMicMuted(_ muted: Bool) async throws
    func suspendCapture() async throws
    func resumeCapture() async throws

    var availableDevices: AsyncStream<[AudioDevice]> { get }
    var effectiveInputDevice: AsyncStream<AudioDevice?> { get }
    var effectiveOutputDevice: AsyncStream<AudioDevice?> { get }
    var events: AsyncStream<AudioCoordinatorEvent> { get }
}

public struct SerenadaConfig {
    public var audioCoordinator: SerenadaAudioCoordinator?  // nil = SDK internal default
    public var audioIntent: AudioIntent                     // defaults shown above
    // ... existing fields
}
```

### Kotlin Protocol

```kotlin
interface SerenadaAudioCoordinator {
    suspend fun activateCallSession(intent: AudioIntent): AudioCoordinatorCapabilities
    suspend fun deactivateCallSession()
    suspend fun applyRouting(device: AudioDevice)
    suspend fun setMicMuted(muted: Boolean)
    suspend fun suspendCapture()
    suspend fun resumeCapture()

    val availableDevices: StateFlow<List<AudioDevice>>
    val effectiveInputDevice: StateFlow<AudioDevice?>
    val effectiveOutputDevice: StateFlow<AudioDevice?>
    val events: SharedFlow<AudioCoordinatorEvent>
}

data class SerenadaConfig(
    val audioCoordinator: SerenadaAudioCoordinator? = null,
    val audioIntent: AudioIntent = AudioIntent(),
    // ... existing fields
)
```

### Public Session API

The session exposes UI-facing properties sourced from the coordinator, plus selection and mute methods:

```
session.availableAudioDevices: StateFlow<[AudioDevice]>
session.currentAudioDevice: StateFlow<AudioDevice>
session.isMicMuted: StateFlow<Bool>
session.isMicMutedByExternalAudio: StateFlow<Bool>   // true while PTT mute is held
session.selectAudioDevice(device): records user override, calls coordinator.applyRouting
session.setMicMuted(muted): user-initiated mute (separate from interruption-driven mute)
```

`isMicMuted` reflects the effective mic state. `isMicMutedByExternalAudio` separates user-initiated mute from auto-mute-during-PTT so the UI can show distinct affordances ("Muted" vs "Muted (PTT active)").

### Mute State Composition

The session tracks two independent mute bits plus a runtime route fact. This is part of the contract, not an open question, because PTT-end must not accidentally unmute a user-muted call.

- `userMuted: Bool` — set by `session.setMicMuted(muted)`. Persists across interruptions.
- `externalAudioMuted: Bool` — set automatically when the coordinator reports an interruption (e.g. `audioSessionInterrupted(.pttTransmission)`) **if** `intent.muteOwnMicDuringExternalAudio` is true. Cleared on `audioSessionResumed`.
- `routeInputAvailable: Bool` — derived from the coordinator's `effectiveInputDevice`. False when no input route exists (mic permission lost, input device disconnected, host capture lock held on a serialized-capture Android device).

Effective WebRTC sender enabled = `!userMuted && !externalAudioMuted && routeInputAvailable`.

Public observables:

- `session.isMicMuted` = `userMuted || externalAudioMuted || !routeInputAvailable`
- `session.isMicMutedByExternalAudio` = `externalAudioMuted` only

The resume path is "clear `externalAudioMuted` and recompute effective sender enabled" — never "blindly call `track.setEnabled(true)`". Same shape on iOS and Android.

### WebRTC Lifecycle Contract

Ordering is explicit and the same on both platforms:

1. `coordinator.activateCallSession(intent)` completes (await), returning capabilities
2. SDK applies initial routing based on capabilities and policy
3. SDK enables WebRTC audio I/O: iOS sets `RTCAudioSession.isAudioEnabled = true`; Android starts `JavaAudioDeviceModule` capture
4. Call runs
5. On teardown: SDK disables WebRTC audio I/O first
6. SDK calls `coordinator.deactivateCallSession()` (await)

This contract holds regardless of whether the coordinator is default or host-supplied. Default coordinator does the work synchronously enough that the ordering is invisible; host coordinator may take meaningful time (e.g., joining `AudioSessionManager`'s queue), and the SDK must await before flipping WebRTC audio on/off.

On iOS, the SDK also configures `RTCAudioSession.useManualAudio = true` at SDK init. This decouples WebRTC's audio plumbing from `AVAudioSession` activation events. Without manual mode, WebRTC's iOS ADM tries to manage `AVAudioSession` itself, which fights any coordinator that owns activation. With manual mode, the SDK alone decides when WebRTC's audio I/O runs, gated on coordinator state.

## Sequences

### Sequence 1: Serenada Standalone

```
App: SerenadaCore.join(url, config)                  // audioCoordinator: nil
SDK: Instantiates internal default coordinator
SDK: caps = await coordinator.activateCallSession(intent: video=true, prox=true)
  Default iOS:     setCategory(.playAndRecord, .voiceChat, [.allowBluetooth, ...])
                   setActive(true)
                   subscribe to routeChangeNotification
                   emit availableDevicesChanged([wired, speaker, earpiece, ...])
                   return capabilities(pttPolicy: .block, sessionOwnership: .sdkOwned, ...)
  Default Android: requestAudioFocus(...)
                   setMode(MODE_IN_COMMUNICATION)
                   subscribe to AudioDeviceCallback
                   return capabilities(pttPolicy: .block, sessionOwnership: .sdkOwned, ...)
SDK: Applies ranking, await coordinator.applyRouting(.bluetoothHeadset)
  Default iOS:     setPreferredInput / overrideOutputAudioPort
  Default Android: setCommunicationDevice(BT_SCO) or startBluetoothSco
SDK: RTCAudioSession.isAudioEnabled = true / ADM starts capture

[Call runs]

User: Taps speaker in UI
SDK: session.selectAudioDevice(.speakerphone)
SDK: Records user override, await coordinator.applyRouting(.speakerphone)

[Call ends]

SDK: RTCAudioSession.isAudioEnabled = false / ADM stops capture
SDK: await coordinator.deactivateCallSession()
  Default iOS:     setActive(false, .notifyOthersOnDeactivation)
                   unsubscribe routeChangeNotification
  Default Android: abandonAudioFocus, setMode(NORMAL), clearCommunicationDevice
                   unsubscribe AudioDeviceCallback
```

Externally observable behavior: identical to today.

### Sequence 2: Zello Embeds Serenada, No PTT Activity

```
Zello: SerenadaCore.join(url, config(audioCoordinator: ZelloAudioCoordinator()))
SDK: caps = await coordinator.activateCallSession(intent: video=true)
  Zello iOS:     dispatch onto AudioConcurrency queue
                 transition AudioSessionManager state -> .recording
                   (sets .playAndRecord + Zello's options + setActive(true))
                 if PTT framework owns the session, skip setActive
                 subscribe to route changes via existing AudioSessionRouteParser
                 enumerate devices
                 return capabilities(pttPolicy: .coexist,
                                     canShareInput: true,
                                     sessionOwnership: .hostOwned or .systemOwned)
  Zello Android: AudioManagerImpl.acquireAudioDevice()
                 (increments _devCounter, starts SCO if BT on-demand)
                 NO setMode call (Zello policy: never set mode)
                 enumerate devices via BluetoothAudioImpl
                 return capabilities(pttPolicy: .coexist,
                                     canShareInput: depends-on-device,
                                     sessionOwnership: .hostOwned)
SDK: Applies ranking, await coordinator.applyRouting(.bluetoothHeadset)
  Zello Android: routes through BluetoothAudioImpl
  Zello iOS:     setPreferredInput through AudioSessionManager
SDK: RTCAudioSession.isAudioEnabled = true (iOS) / ADM starts (Android)

[Call ends]

SDK: RTCAudioSession.isAudioEnabled = false / ADM stops
SDK: await coordinator.deactivateCallSession()
  Zello Android: releaseAudioDevice() with grace timer
  Zello iOS:     transition state machine back to .playing or .inactive
```

### Sequence 3: Zello PTT While Serenada Call Active (Default Coexist)

```
[Serenada call active, mic capturing, BT routing via Zello stack]

Zello: User keys PTT (or incoming PTT arrives via PTChannelManager)
Zello: ZelloAudioCoordinator.events emits audioSessionInterrupted(.pttTransmission)
SDK:   Observes event. Consults capabilities (pttPolicy: .coexist) and
       intent (muteOwnMicDuringExternalAudio: true,
               duckOwnPlaybackDuringExternalAudio: true).
SDK:   Set externalAudioMuted = true (effective WebRTC sender goes false via
         composition rule in Mute State Composition).
       Lower remote audio track receiver volume to ducked level.
       session.isMicMutedByExternalAudio observable flips to true.
       Call stays connected, video stays live, signaling stays alive.
       UI shows "PTT active" badge.

[Zello transmits/receives PTT audio through its own pipeline.
 On iOS: both pipelines share the active AVAudioSession.
 On Android (most devices): both pipelines run independent AudioRecord/AudioTrack.]

Zello: PTT ends. Framework calls didDeactivate (iOS), or Zello signals end (Android).
Zello: On iOS, host re-activates AVAudioSession with Serenada's category immediately.
Zello: events emits audioSessionResumed.
SDK:   Clear externalAudioMuted bit and recompute effective sender enabled.
         If user had muted manually mid-PTT, sender stays disabled and
         session.isMicMuted continues to reflect that.
       Restore remote audio track volume.
       session.isMicMutedByExternalAudio observable flips to false.
       On iOS: ~100-200ms audio gap is bounded by host re-activation speed.
```

If `muteOwnMicDuringExternalAudio = false` in the intent, SDK skips the mic mute step but still emits `isMicMutedByExternalAudio` events that observers can use (e.g., a host that handles the mute via their own UI layer).

### Sequence 3-Fallback: Android Serialized-Capture Device

```
[Serenada call active. Device cannot share mic between AudioRecord instances.]

Zello: User keys PTT. Zello's PTT pipeline needs the mic.
Zello: events emits inputUnavailable
SDK:   Observes. Consults capabilities (canShareInput: false).
       await coordinator.suspendCapture() — stops ADM input entirely.
       Sets session.isMicMutedByExternalAudio = true.
       Remote audio continues playing.
       Call stays connected.
Zello: PTT ends.
Zello: events emits inputAvailable
SDK:   await coordinator.resumeCapture() — restarts ADM input.
       Sets session.isMicMutedByExternalAudio = false.
```

The capture suspend/resume primitive is a new method on the internal `SessionMediaEngine` interface, not just sender-track toggling. The Android default coordinator implements no-op suspend/resume (its capture is never externally preempted). Zello's coordinator implements the real version: it tells the SDK when its own `AudioRecord` is competing for the input.

### Sequence 3-Preempt: Host Opts Into Call Termination

```
[Coordinator publishes pttPolicy: .preempt]
Zello: events emits audioSessionInterrupted(.pttTransmission)
SDK:   Consults policy (.preempt). Terminates call with endedReason: .pttPreempted.
       App layer can offer reconnect after PTT ends.
```

This path is reserved for hosts that have explicitly opted in (e.g., a truck-driver deployment where the radio always wins). Not the default.

## Open Questions

1. **Threading and event delivery.** iOS coordinator runs on its own queue; SDK calls into it from the session actor. Need a "do not block in event handlers" contract so coordinator event delivery doesn't deadlock against an in-flight `applyRouting` await.

2. **Coordinator activation failure.** If `activateCallSession` throws, the call cannot start. SDK surfaces a clear error code so the host can show "audio busy" or similar. Decide which error codes are coordinator-supplied vs SDK-defined.

3. **Cellular call mid-Serenada-call.** Both default coordinator and host coordinator must handle. iOS: phone call interrupts via standard interruption flow, emits `audioSessionInterrupted(.phoneCall)`. Android: `PhoneCallStateMonitor` style detection. Already works in standalone; needs verification in coordinator model.

4. **iOS deactivation gap behavior.** The ~100-200ms gap when PTT framework deactivates and host re-activates is bounded but real. Decide whether to expose it as a `briefAudioGap` event (so UI can show a tiny "reconnecting audio" affordance) or treat as transparent. Default: transparent, no UI surface.

5. **Headset hot-swap during PTT.** Multi-actor: PTT in flight, BT disconnects, Serenada has a call active. Coordinator emits `availableDevicesChanged` and `effectiveRouteChanged`. SDK re-ranks. If new effective route happens during PTT mute, the user doesn't hear the route change confirm, but the state is correct on resume.

6. **AirPods auto-switching.** iOS may auto-route to AirPods when proximity is detected. Default coordinator must respect; Zello coordinator must respect. Verify both paths.

7. **Long PTT and ICE freshness.** WebRTC's ICE consent freshness check fires every 30s by default. A PTT moment is rarely that long, but for hosts that opt into very long suspensions (e.g., `pttPolicy: .preempt` with reconnect-after path), the SDK may need to issue a soft ICE refresh on resume. Probably overengineering for v1.

8. **Backwards compatibility.** Public API additions only. Minor version bump.

## Implementation Plan

Sequenced so each step is shippable and verifiable in isolation.

### Step 1: Internal Refactor, iOS

- Extract the `SerenadaAudioCoordinator` protocol from the existing internal `SessionAudioController` in `SerenadaCore/Sources/SerenadaSession.swift`
- Move `CallAudioSessionController` to an internal default coordinator that conforms to `SerenadaAudioCoordinator`
- Introduce `RTCAudioSession.useManualAudio = true` at SDK init; gate `isAudioEnabled` on coordinator state
- Migrate route-change subscription from `CallAudioSessionController` into the coordinator's responsibility surface
- Add capture suspend/resume scaffolding to the internal media engine interface (no-op default impl)
- No public API change yet. Verify standalone behavior unchanged via existing test plan.

### Step 2: Internal Refactor, Android

- Same extraction in `client-android/serenada-core/`. `CallAudioSessionController` becomes the internal default coordinator.
- Add `suspendCapture` / `resumeCapture` to `SessionMediaEngine` and `WebRtcEngine`. Default impl stops/starts the `JavaAudioDeviceModule` audio input on Android. Test that round-trip resume returns audio within an acceptable window.
- Verify standalone behavior unchanged.

### Step 3: Public API, Both Platforms

- Expose `SerenadaAudioCoordinator` protocol publicly
- Expose `AudioDevice`, `AudioIntent`, `AudioCoordinatorEvent`, `AudioCoordinatorCapabilities`, `PttPolicy`, `SessionOwnership` as public types
- Add `audioCoordinator: SerenadaAudioCoordinator?` and `audioIntent: AudioIntent` to `SerenadaConfig`
- Expose `availableAudioDevices`, `currentAudioDevice`, `isMicMuted`, `isMicMutedByExternalAudio`, `selectAudioDevice`, `setMicMuted` on `SerenadaSession`
- Implement the three mute-state composition (user / external / route) on the session
- Document the protocol contract, threading expectations, WebRTC lifecycle contract, and all three sequences in the SDK README
- Bump SDK minor version, update `scripts/check-version-parity.mjs` to pass

### Step 4: Sample Coordinator

- Add a sample non-default coordinator implementation in `samples/ios/` and `samples/android/`
- The sample shows: how to enumerate devices through host audio infra, how to emit `audioSessionInterrupted(.pttTransmission)` on a fake PTT event, how to handle re-activation
- Useful as both documentation and a test bed

### Step 5: Zello Integration (separate effort, owned by Zello)

- Zello implements `ZelloAudioCoordinator` on top of its existing audio infrastructure
- Validation: Serenada call + Zello PTT, both Android and iOS, with `pttPolicy: .coexist` and default mute-mic / duck-playback flags
- Validation: Serenada call + Zello PTT on an Android device with serialized capture, verifying the suspend/resume path
- Validation: opt-in `pttPolicy: .preempt` deployment scenario
