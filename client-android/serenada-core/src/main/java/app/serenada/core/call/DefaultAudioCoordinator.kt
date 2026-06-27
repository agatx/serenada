package app.serenada.core.call

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import app.serenada.core.ForegroundMediaArbiter
import app.serenada.core.ForegroundOwnerToken
import app.serenada.core.SerenadaLogLevel
import app.serenada.core.SerenadaLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

internal class DefaultAudioCoordinator(
    context: Context,
    private val handler: Handler,
    private val proximityMonitoringEnabled: Boolean,
    private val onProximityChanged: (Boolean) -> Unit,
    private val onAudioEnvironmentChanged: () -> Unit,
    private val logger: SerenadaLogger? = null,
) : SessionAudioController, SerenadaAudioCoordinator {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var audioSessionActive = false

    // --- Foreground-lease fence (multi-call session, Phase 4; contract §6) ---
    //
    // DefaultAudioCoordinator is process-global: every instance mutates the SAME
    // AudioManager (audio focus, MODE_IN_COMMUNICATION, setCommunicationDevice/SCO).
    // Its DELAYED/ASYNC paths (postDelayed route-refresh + ducking-fallback, the
    // AudioDeviceCallback route monitor, the proximity SensorEventListener, the
    // focus re-request) and its deactivation can fire AFTER the foreground moved on,
    // re-driving the OS audio for the WRONG (stale) activation. To prevent that the
    // session binds the arbiter-minted lease token this coordinator activated under
    // PLUS the operation generation, and EVERY OS-touching delayed/async/listener
    // path fences on BOTH (contract §3 two-fence rule):
    //
    //  1. OWNER TOKEN ([isStillLeaseOwner]): no-op once a DIFFERENT call became the
    //     arbiter's lease owner (a switch handed off to another session/coordinator).
    //  2. OPERATION GENERATION (this token's value vs [leaseGeneration]): no-op once
    //     a NEWER activation advanced the coordinator's current generation. This is
    //     the independent second fence the token alone cannot provide: a SAME-OWNER
    //     rollback re-acquires the lease for the SAME call under a FRESH generation,
    //     so a stale callback left over from the PRIOR activation attempt of that
    //     same owner still has the matching token. Without the generation check it
    //     would survive the token fence and drive the OS for a superseded attempt.
    //
    // [bindForegroundLease] advances [leaseGeneration] on EVERY activation (initial,
    // resume, rollback re-activation). A delayed callback / armed listener / pending
    // deactivation captures the generation in effect when it was scheduled/armed and
    // drops itself once [leaseGeneration] has advanced past it.
    //
    // SINGLE-CALL / FAKE PATH: when [leaseToken] is null no callback is ever
    // dropped — [isStillLeaseOwner] short-circuits to true and [bindForegroundLease]
    // is never called, so a captured generation always equals [leaseGeneration] (both
    // stay 0L). Behavior is identical to before this fence. The direct single-call
    // join binds its own DIRECT-mode lease token, so it self-fences correctly too.
    private var leaseToken: ForegroundOwnerToken? = null

    // The coordinator's CURRENT operation generation: the value of the most recent
    // [bindForegroundLease]. The authoritative "generation in effect" that scheduled
    // callbacks / armed listeners compare against (the second fence). Advances on
    // every (re-)activation; a callback captured under an older value is stale.
    private var leaseGeneration: Long = 0L

    // Generation captured when the long-lived listeners (route monitor, proximity,
    // focus re-request) were armed at activation. Re-armed on a same-coordinator
    // re-activation so the LIVE call's listeners keep firing; a listener whose armed
    // generation is older than [leaseGeneration] belongs to a superseded attempt and
    // no-ops.
    private var armedGeneration: Long = 0L

    // Generation captured when the postDelayed ducking-fallback runnable was last
    // scheduled. The runnable is a single shared instance, so it cannot close over a
    // per-schedule generation; this field carries it instead.
    private var duckingFallbackGeneration: Long = 0L

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false
    private var previousMicrophoneMute = false
    private var proximityMonitoringActive = false
    private var isProximityNear = false
    private var proximityEarpieceEnabled = true
    private var audioDeviceMonitoringActive = false
    private var communicationDeviceChangedListener: Any? = null
    private var bluetoothScoActive = false
    private var pinnedOutputDevice: AudioDevice? = null
    private var pinnedOutputRouteInventory: Set<String>? = null
    private val communicationDeviceExecutor = Executor { command ->
        handler.post(command)
    }

    private val _availableDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val availableDevices: StateFlow<List<AudioDevice>> = _availableDevices.asStateFlow()

    private val _effectiveInputDevice = MutableStateFlow<AudioDevice?>(null)
    override val effectiveInputDevice: StateFlow<AudioDevice?> = _effectiveInputDevice.asStateFlow()

    private val _effectiveOutputDevice = MutableStateFlow<AudioDevice?>(null)
    override val effectiveOutputDevice: StateFlow<AudioDevice?> = _effectiveOutputDevice.asStateFlow()

    private val _events = MutableSharedFlow<AudioCoordinatorEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AudioCoordinatorEvent> = _events.asSharedFlow()
    private val playbackDuckingFallbackRunnable = Runnable {
        // Fence the postDelayed (~3000ms) ducking fallback on BOTH the owner token
        // AND the generation it was scheduled under (contract §3 two-fence rule): a
        // superseded session — including a SAME-OWNER prior attempt whose token still
        // matches after a fresh-generation re-activation — must not emit a
        // route/ducking event that drives the (now-current) call's playback.
        if (!isStillLeaseOwner(duckingFallbackGeneration)) return@Runnable
        _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus changed: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                clearPlaybackDuckingFallback()
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioStarted)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingStarted)
                schedulePlaybackDuckingFallback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                clearPlaybackDuckingFallback()
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
                audioFocusGranted = false
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioStarted)
                val focusReRequestGeneration = leaseGeneration
                handler.post {
                    // A superseded session must not re-grab audio focus from the
                    // call that now owns the lease (contract §3 two-fence rule):
                    // fence on the owner token AND the generation captured when this
                    // re-request was posted, so a same-owner prior attempt drops too.
                    if (!audioSessionActive || !isStillLeaseOwner(focusReRequestGeneration)) return@post
                    requestAudioFocus(emitRecoveryEventOnGain = true)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                clearPlaybackDuckingFallback()
                audioFocusGranted = true
                _events.tryEmit(AudioCoordinatorEvent.ExternalAudioEnded)
                _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
            }
            else -> Unit
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            onAudioDevicesChanged()
        }
    }

    private val proximitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // Proximity earpiece behavior is driven by the CURRENT foreground lease
            // owner only (contract §3 two-fence rule): a superseded session — by a
            // different owner OR a stale same-owner attempt under the armed
            // generation — must not flip the shared route / pause video.
            if (!isStillLeaseOwner(armedGeneration)) return
            val maxRange = proximitySensor?.maximumRange ?: return
            val distance = event.values.firstOrNull() ?: return
            val near = distance < maxRange
            if (near == isProximityNear) return
            isProximityNear = near
            onProximityChanged(near)
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
            _events.tryEmit(AudioCoordinatorEvent.EffectiveRouteChanged(_effectiveInputDevice.value, _effectiveOutputDevice.value))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Bind the arbiter-minted foreground-lease [token] and operation [generation]
     * this coordinator is activating under (multi-call session, Phase 4; contract
     * §3/§6). The session calls this immediately before EVERY `activateCallSession`
     * — initial join, resume, and a rollback re-activation — so [leaseGeneration]
     * advances on each, becoming the coordinator's authoritative "current
     * generation". Every delayed/async/listener path fences against both this token
     * and this generation. A null token (single-call without arbiter routing, or a
     * fake coordinator in tests) leaves the fence in pass-through mode: this
     * coordinator is always treated as the owner and no callback is dropped.
     */
    internal fun bindForegroundLease(token: ForegroundOwnerToken?, generation: Long) {
        leaseToken = token
        leaseGeneration = generation
    }

    /**
     * Snapshot the coordinator's CURRENT operation generation at the moment the
     * session ENQUEUES/REQUESTS an async deactivation (multi-call session, Phase 4;
     * contract §6). Passed back into [deactivateCallSession] so the restore decision
     * fences against the generation in effect when the deactivation was ASKED FOR —
     * not the mutable [leaseGeneration] the coordinator happens to hold when the
     * chained Job finally runs. A later same-coordinator re-activation advances
     * [leaseGeneration] (and re-arms [armedGeneration]); without a request-time
     * snapshot a stale deactivation would read the refreshed generation and wrongly
     * restore MODE_NORMAL over the now-live attempt (the §3 generation fence,
     * mirroring iOS's request-time `installedLeaseSnapshot()`).
     */
    internal fun leaseGenerationSnapshot(): Long = leaseGeneration

    /**
     * Token fence: true iff this coordinator may still touch the process-global
     * AudioManager on the OWNER axis — either no lease was bound (single-call / test
     * pass-through), or the bound lease token is STILL the arbiter's live foreground
     * owner. Once a DIFFERENT call acquires the lease, an old session's coordinator
     * returns false here.
     *
     * NOTE: this is necessary but NOT sufficient — a SAME-OWNER rollback keeps the
     * token current while bumping the generation, so callers that can race a
     * re-activation MUST use the [isStillLeaseOwner] generation overload below. This
     * no-arg form exists only as the shared token check it composes.
     */
    private fun isStillLeaseOwner(): Boolean {
        val token = leaseToken ?: return true
        return ForegroundMediaArbiter.isCurrentOwner(token)
    }

    /**
     * Two-fence check (contract §3): true iff this coordinator is STILL the live
     * foreground owner AND no newer activation has advanced the generation past
     * [scheduledGeneration] — the value [leaseGeneration] held when this callback was
     * scheduled / this listener was armed. A delayed callback or listener from a
     * superseded attempt (a different owner, OR a same-owner attempt re-activated
     * under a fresh generation) fails one of the two fences and no-ops, so it never
     * re-drives focus / mode / route / proximity for a stale activation.
     *
     * Pass-through (no token bound) keeps returning true: [bindForegroundLease] is
     * never called, so both values stay 0L and the generation fence always passes.
     */
    private fun isStillLeaseOwner(scheduledGeneration: Long): Boolean {
        return scheduledGeneration == leaseGeneration && isStillLeaseOwner()
    }

    /**
     * True iff this coordinator may restore the OS audio (mode / route / focus) on
     * [deactivate]. Two independent reasons to SKIP the restore (contract §3
     * two-fence rule), either of which means a newer activation owns the OS audio:
     *
     *  1. OWNER axis ([ForegroundMediaArbiter.hasOtherOwner]): a DIFFERENT call now
     *     owns the lease — a switch handed off to another session/coordinator.
     *     Distinct from [isStillLeaseOwner]: a clean single-call END releases the
     *     lease BEFORE the async deactivation runs, so the bound token is no longer
     *     "current", yet there is NO newer owner, so the restore is correct and must
     *     proceed (this is what keeps a clean last-call leave restoring MODE_NORMAL).
     *  2. GENERATION axis ([requestGeneration] vs [leaseGeneration]): a SAME-OWNER
     *     re-activation bumped this coordinator's generation after the deactivation
     *     was REQUESTED. The token still matches (same owner), so only the generation
     *     reveals that a newer attempt is now driving the OS audio and the stale
     *     deactivation must not restore MODE_NORMAL over it.
     *
     * CRITICAL ([requestGeneration] is captured at REQUEST time): the session
     * snapshots the generation when it ENQUEUES the async deactivation Job (via
     * [leaseGenerationSnapshot]) and passes it here. It must NOT be the mutable
     * [armedGeneration], which a same-coordinator re-activation refreshes to the
     * current generation in [activateCallSession]; reading that at run time would let
     * a deactivation requested under generation N compare `N+1 == N+1` after an N+1
     * re-activation and wrongly restore over the live attempt.
     *
     * Pass-through (no token bound) always restores: [hasOtherOwner] is false for a
     * null token and the captured generation equals [leaseGeneration] (both 0L).
     */
    private fun mayRestoreOnDeactivate(requestGeneration: Long): Boolean {
        if (ForegroundMediaArbiter.hasOtherOwner(leaseToken)) return false
        return requestGeneration == leaseGeneration
    }

    override fun activate() {
        if (audioSessionActive) return
        audioSessionActive = true
        // Arm the long-lived listeners (route monitor / proximity / focus re-request)
        // under the generation currently in effect (set by the preceding
        // [bindForegroundLease]), so a stale listener callback drops once a newer
        // activation advances [leaseGeneration] (contract §3 two-fence rule). The
        // deactivation restore does NOT use [armedGeneration]: it fences against the
        // generation captured at REQUEST time (see [deactivate]).
        armedGeneration = leaseGeneration
        previousAudioMode = audioManager.mode
        previousSpeakerphoneOn = isSpeakerphoneEnabled()
        previousMicrophoneMute = audioManager.isMicrophoneMute
        requestAudioFocus()
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false
            startAudioDeviceMonitoring()
            if (proximityMonitoringEnabled && proximityEarpieceEnabled) {
                startProximityMonitoring()
            }
            updateDevicesAndRoute()
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
        }.onSuccess {
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "Audio",
                "Audio session activated (prevMode=$previousAudioMode, focusGranted=$audioFocusGranted)"
            )
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to activate audio session: ${error.message}")
        }
    }

    /**
     * Synchronous teardown ([SessionAudioController.deactivate]). Captures the
     * coordinator's CURRENT generation as the request generation: this entry point
     * runs inline on the main thread (no chained Job between request and run), so
     * "now" IS request time. The async [deactivateCallSession] path captures the
     * generation EARLIER (at enqueue) and routes through [deactivate] below.
     */
    override fun deactivate() {
        deactivate(leaseGeneration)
    }

    /**
     * Tear down the OS audio, fencing the RESTORE against [requestGeneration] — the
     * coordinator generation in effect when this deactivation was REQUESTED (contract
     * §6). The async session path captures it at enqueue (via [leaseGenerationSnapshot])
     * so a same-coordinator re-activation that advances [leaseGeneration] between
     * request and run cannot let a stale deactivation restore MODE_NORMAL over the
     * live attempt.
     */
    private fun deactivate(requestGeneration: Long) {
        if (!audioSessionActive) {
            abandonAudioFocus()
            return
        }
        audioSessionActive = false
        proximityEarpieceEnabled = true
        pinnedOutputDevice = null
        pinnedOutputRouteInventory = null
        clearPlaybackDuckingFallback()
        stopProximityMonitoring()
        stopAudioDeviceMonitoring()
        // FENCE (contract §3 two-fence rule): a deactivation from a SUPERSEDED
        // attempt must NOT restore MODE_NORMAL / the previous route after a NEWER
        // activation already set MODE_IN_COMMUNICATION. All coordinators mutate the
        // SAME process-global AudioManager, so a stale async deactivation Job would
        // otherwise clobber the current foreground call's mode/route. Restore is
        // skipped when EITHER a DIFFERENT call now owns the lease (cross-coordinator
        // handoff) OR a same-coordinator re-activation advanced the generation past
        // [requestGeneration] (a same-owner rollback the token alone cannot detect).
        // [requestGeneration] is the generation captured at REQUEST time — NOT the
        // mutable armedGeneration, which a re-activation refreshes to the current
        // value. A clean single-call end (lease already released, no newer owner,
        // generation unchanged) and the no-token pass-through both still restore
        // exactly as before.
        if (mayRestoreOnDeactivate(requestGeneration)) {
            runCatching {
                setLegacyBluetoothScoRouting(false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.isMicrophoneMute = previousMicrophoneMute
                setSpeakerphoneEnabled(previousSpeakerphoneOn)
                audioManager.mode = previousAudioMode
            }.onSuccess {
                logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio session restored (mode=$previousAudioMode)")
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to restore audio session: ${error.message}")
            }
        } else {
            logger?.log(
                SerenadaLogLevel.DEBUG,
                "Audio",
                "Superseded attempt deactivating; skipping OS restore (a newer " +
                    "activation owns the OS audio; requestGen=$requestGeneration, " +
                    "currentGen=$leaseGeneration)",
            )
        }
        abandonAudioFocus()
    }

    override fun shouldPauseVideoForProximity(isScreenSharing: Boolean): Boolean {
        return proximityMonitoringActive &&
            isProximityNear &&
            !isScreenSharing &&
            !isBluetoothHeadsetConnected()
    }

    // MARK: - SerenadaAudioCoordinator Conformance

    override suspend fun activateCallSession(intent: AudioIntent) {
        proximityEarpieceEnabled = intent.enableProximityEarpiece
        if (audioSessionActive) {
            // Same-coordinator re-activation (e.g. resume / rollback): re-arm the
            // long-lived listeners to the now-current generation so the LIVE call's
            // callbacks keep firing while any callback armed under the prior
            // generation still drops (contract §3 two-fence rule). NOTE: this is
            // exactly why the deactivation restore must NOT read [armedGeneration] —
            // refreshing it here would let a deactivation requested under the PRIOR
            // generation see the bumped value and wrongly restore. The deactivation
            // restore fences against a REQUEST-time snapshot instead (see [deactivate]).
            armedGeneration = leaseGeneration
            updateProximityMonitoringForIntent()
            applyCallAudioRouting()
            updateDevicesAndRoute()
            onAudioEnvironmentChanged()
        } else {
            activate()
        }
        intent.preferredDevice?.let { applyRouting(it) }
    }

    override suspend fun deactivateCallSession() {
        // Public-protocol entry (custom-coordinator parity / unfenced teardown):
        // captures the current generation as the request generation. The lease-aware
        // async session path uses [deactivateCallSession] with an explicit
        // request-time generation (below) so a fresher re-activation between request
        // and run cannot mask a stale deactivate.
        deactivate(leaseGeneration)
    }

    /**
     * Deactivate fenced by [requestGeneration] — the coordinator generation captured
     * at the moment the session ENQUEUED this deactivation (multi-call session, Phase
     * 4; contract §6). The session snapshots it synchronously via
     * [leaseGenerationSnapshot] at enqueue time and passes it here so the restore
     * decision compares the REQUEST-time generation (not the mutable, re-activation-
     * refreshed [armedGeneration]) against the current [leaseGeneration]. Mirrors
     * iOS's request-time `deactivateCallSession(fencedBy:)`.
     */
    internal suspend fun deactivateCallSession(requestGeneration: Long) {
        deactivate(requestGeneration)
    }

    override suspend fun applyRouting(device: AudioDevice) {
        if (!device.isOutputRoute()) return
        pinnedOutputDevice = device
        pinnedOutputRouteInventory = currentOutputRouteInventory()
        applyOutputRoute(device)
        // This runs synchronously under the current foreground; fence the immediate
        // refresh on the current generation (contract §3 two-fence rule).
        refreshDevicesAndRouteFromSystem(leaseGeneration)
    }

    private fun applyOutputRoute(device: AudioDevice) {
        when (device.kind) {
            is AudioDeviceKind.Speakerphone -> routeAudioToSpeaker()
            is AudioDeviceKind.Earpiece -> routeAudioToEarpiece()
            is AudioDeviceKind.Bluetooth -> routeAudioToBluetooth(device)
            else -> routeAudioToExternal(device)
        }
        scheduleRouteRefreshFromSystem()
    }

    private fun scheduleRouteRefreshFromSystem() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Capture the generation in effect at schedule time so the delayed refresh
        // fences on BOTH token and generation when it fires (contract §3).
        val scheduledGeneration = leaseGeneration
        handler.postDelayed(
            { refreshDevicesAndRouteFromSystem(scheduledGeneration) },
            COMMUNICATION_ROUTE_REFRESH_DELAY_MS
        )
    }

    private fun refreshDevicesAndRouteFromSystem(scheduledGeneration: Long) {
        // Fence the postDelayed (~300ms) route refresh on BOTH token and generation
        // (contract §3 two-fence rule): drop it if this coordinator is no longer the
        // lease owner (a different call took over) OR a newer activation advanced the
        // generation past [scheduledGeneration] (a same-owner rollback the token
        // alone cannot detect).
        if (!audioSessionActive || !isStillLeaseOwner(scheduledGeneration)) return
        updateDevicesAndRoute()
        onAudioEnvironmentChanged()
        _events.tryEmit(AudioCoordinatorEvent.EffectiveRouteChanged(_effectiveInputDevice.value, _effectiveOutputDevice.value))
    }

    override suspend fun setMicMuted(muted: Boolean) {
        // No-op to avoid mutating process-global AudioManager.isMicrophoneMute
        if (!muted) {
            ensureAudioFocus()
        }
    }

    private fun ensureAudioFocus() {
        if (!audioFocusGranted) {
            requestAudioFocus()
        }
    }

    private fun onAudioDevicesChanged() {
        // Route monitoring applies only for the CURRENT foreground lease owner
        // (contract §3 two-fence rule): a superseded attempt — a different owner OR a
        // stale same-owner attempt under the armed generation — must not re-route the
        // shared AudioManager when devices change.
        if (!audioSessionActive || !isStillLeaseOwner(armedGeneration)) return
        updateDevicesAndRoute()
        applyCallAudioRouting()
        refreshDevicesAndRouteFromSystem(armedGeneration)
    }

    private fun onCommunicationDeviceChanged() {
        if (!isStillLeaseOwner(armedGeneration)) return
        refreshDevicesAndRouteFromSystem(armedGeneration)
    }

    private fun startProximityMonitoring() {
        if (proximityMonitoringActive) return
        val manager = sensorManager ?: return
        val sensor = proximitySensor ?: return
        val registered = runCatching {
            manager.registerListener(
                proximitySensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                handler
            )
        }.getOrElse { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register proximity listener: ${error.message}")
            false
        }
        if (registered) {
            proximityMonitoringActive = true
            isProximityNear = false
        }
    }

    private fun stopProximityMonitoring() {
        if (!proximityMonitoringActive) {
            isProximityNear = false
            return
        }
        runCatching {
            sensorManager?.unregisterListener(proximitySensorListener)
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister proximity listener: ${error.message}")
        }
        proximityMonitoringActive = false
        isProximityNear = false
    }

    private fun startAudioDeviceMonitoring() {
        if (!audioDeviceMonitoringActive) {
            runCatching {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
                audioDeviceMonitoringActive = true
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register audio device callback: ${error.message}")
            }
        }
        startCommunicationDeviceMonitoring()
    }

    private fun stopAudioDeviceMonitoring() {
        stopCommunicationDeviceMonitoring()
        if (audioDeviceMonitoringActive) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }.onFailure { error ->
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister audio device callback: ${error.message}")
            }
            audioDeviceMonitoringActive = false
        }
    }

    private fun startCommunicationDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || communicationDeviceChangedListener != null) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener {
            onCommunicationDeviceChanged()
        }
        runCatching {
            audioManager.addOnCommunicationDeviceChangedListener(communicationDeviceExecutor, listener)
            communicationDeviceChangedListener = listener
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to register communication device callback: ${error.message}")
        }
    }

    private fun stopCommunicationDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            communicationDeviceChangedListener = null
            return
        }
        val listener = communicationDeviceChangedListener as? AudioManager.OnCommunicationDeviceChangedListener ?: return
        runCatching {
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to unregister communication device callback: ${error.message}")
        }
        communicationDeviceChangedListener = null
    }

    private fun applyCallAudioRouting() {
        if (!audioSessionActive) return
        clearPinnedOutputIfRouteInventoryChanged()
        pinnedOutputDevice?.let { device ->
            if (isPinnedOutputDeviceAvailable(device)) {
                applyOutputRoute(device)
                return
            }
            pinnedOutputDevice = null
            pinnedOutputRouteInventory = null
        }
        applyOutputRoute(preferredAutomaticOutputDevice())
    }

    private fun preferredAutomaticOutputDevice(): AudioDevice {
        preferredBluetoothOutputDevice()?.let { return it }
        preferredExternalOutputDevice()?.let { return it }
        if (proximityMonitoringActive && isProximityNear) {
            return availableOutputDevice(AudioDeviceKind.Earpiece)
        }
        return availableOutputDevice(AudioDeviceKind.Speakerphone)
    }

    private fun preferredBluetoothOutputDevice(): AudioDevice? {
        val bluetoothDevices = _availableDevices.value
            .filter { it.isOutputRoute() && it.kind is AudioDeviceKind.Bluetooth }
        return bluetoothDevices.firstOrNull { it.status == AudioDeviceStatus.ACTIVE }
            ?: bluetoothDevices.firstOrNull { (it.kind as? AudioDeviceKind.Bluetooth)?.profile == BluetoothProfile.HFP }
            ?: bluetoothDevices.firstOrNull { (it.kind as? AudioDeviceKind.Bluetooth)?.profile == BluetoothProfile.BLE }
            ?: bluetoothDevices.firstOrNull()
    }

    private fun preferredExternalOutputDevice(): AudioDevice? {
        return _availableDevices.value
            .filter { it.isOutputRoute() && it.kind.isExternalOutputRoute() }
            .minWithOrNull(
                compareBy<AudioDevice> { it.kind.automaticRouteRank() }
                    .thenBy { outputRouteInventoryKey(it) }
            )
    }

    private fun clearPinnedOutputIfRouteInventoryChanged() {
        val pinnedInventory = pinnedOutputRouteInventory ?: return
        if (pinnedInventory != currentOutputRouteInventory()) {
            pinnedOutputDevice = null
            pinnedOutputRouteInventory = null
        }
    }

    private fun routeAudioToBluetooth(device: AudioDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothDevice = findCommunicationDevice(device)
                ?: findBluetoothCommunicationDevice()
            if (bluetoothDevice == null || !audioManager.setCommunicationDevice(bluetoothDevice)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to Bluetooth headset")
                pinnedOutputDevice = null
                pinnedOutputRouteInventory = null
                routeAudioToSpeaker()
            }
            return
        }
        setSpeakerphoneEnabled(false)
        setLegacyBluetoothScoRouting(true)
    }

    private fun routeAudioToEarpiece() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setLegacyBluetoothScoRouting(false)
            if (!setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)) {
                routeAudioToSpeaker()
            }
            return
        }
        setLegacyBluetoothScoRouting(false)
        setSpeakerphoneEnabled(false)
    }

    private fun updateProximityMonitoringForIntent() {
        if (proximityMonitoringEnabled && proximityEarpieceEnabled) {
            startProximityMonitoring()
        } else {
            stopProximityMonitoring()
        }
    }

    private fun schedulePlaybackDuckingFallback() {
        clearPlaybackDuckingFallback()
        // Capture the generation in effect for the shared fallback runnable to fence
        // against when it fires (contract §3 two-fence rule).
        duckingFallbackGeneration = leaseGeneration
        handler.postDelayed(playbackDuckingFallbackRunnable, PLAYBACK_DUCKING_FALLBACK_MS)
    }

    private fun clearPlaybackDuckingFallback() {
        handler.removeCallbacks(playbackDuckingFallbackRunnable)
    }

    private fun routeAudioToSpeaker() {
        setLegacyBluetoothScoRouting(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!setCommunicationDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
                audioManager.clearCommunicationDevice()
            }
            return
        }
        setSpeakerphoneEnabled(true)
    }

    private fun routeAudioToExternal(device: AudioDevice) {
        setLegacyBluetoothScoRouting(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!setCommunicationDevice(device)) {
                logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to external device kind=${device.kind}")
            }
            return
        }
        setSpeakerphoneEnabled(false)
    }

    private fun setCommunicationDevice(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.type == type }
            ?: return false
        return audioManager.setCommunicationDevice(device)
    }

    private fun setCommunicationDevice(device: AudioDevice): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val communicationDevice = findCommunicationDevice(device)
            ?: audioManager.availableCommunicationDevices.firstOrNull { info ->
                deviceKindMatches(mapDeviceKind(info.type), device.kind)
            }
            ?: return false
        return audioManager.setCommunicationDevice(communicationDevice)
    }

    private fun findCommunicationDevice(device: AudioDevice): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { info ->
            info.id.toString() == device.id
        }
    }

    private fun isBluetoothHeadsetConnected(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            findBluetoothCommunicationDevice() != null
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                isBluetoothHeadsetType(device.type)
            }
        }
    }

    private fun findBluetoothCommunicationDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return audioManager.availableCommunicationDevices.firstOrNull { device ->
            isBluetoothHeadsetType(device.type)
        }
    }

    private fun isBluetoothHeadsetType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    @Suppress("DEPRECATION")
    private fun setLegacyBluetoothScoRouting(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothScoActive = false
            return
        }
        if (enabled) {
            if (!bluetoothScoActive) {
                audioManager.startBluetoothSco()
                bluetoothScoActive = true
            }
            audioManager.isBluetoothScoOn = true
            return
        }
        if (bluetoothScoActive) {
            audioManager.stopBluetoothSco()
            bluetoothScoActive = false
        }
        audioManager.isBluetoothScoOn = false
    }

    private fun isSpeakerphoneEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    private fun setSpeakerphoneEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker == null || !audioManager.setCommunicationDevice(speaker)) {
                    logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to route audio to built-in speaker")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun isPinnedOutputDeviceAvailable(pinnedDevice: AudioDevice): Boolean {
        return _availableDevices.value.any { device ->
            device.isOutputRoute() && outputRouteInventoryKey(device) == outputRouteInventoryKey(pinnedDevice)
        }
    }

    private fun currentOutputRouteInventory(): Set<String> {
        return _availableDevices.value
            .filter { it.isOutputRoute() }
            .map { outputRouteInventoryKey(it) }
            .toSet()
    }

    private fun outputRouteInventoryKey(device: AudioDevice): String {
        val routeName = device.displayName.trim()
        val fallback = device.id.ifEmpty { routeName }
        return when (device.kind) {
            is AudioDeviceKind.Speakerphone -> "speakerphone"
            is AudioDeviceKind.Earpiece -> "earpiece"
            is AudioDeviceKind.Bluetooth -> "bluetooth:$fallback"
            is AudioDeviceKind.WiredHeadset -> "wired"
            is AudioDeviceKind.CarAudio -> "car:$fallback"
            is AudioDeviceKind.Usb -> "usb:$fallback"
            is AudioDeviceKind.Other -> "other:$fallback"
        }
    }

    private fun requestAudioFocus(emitRecoveryEventOnGain: Boolean = false) {
        val wasGranted = audioFocusGranted
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request =
                audioFocusRequest
                    ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build()
                        .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        audioFocusGranted = granted
        if (emitRecoveryEventOnGain && granted && !wasGranted) {
            _events.tryEmit(AudioCoordinatorEvent.ExternalAudioEnded)
            _events.tryEmit(AudioCoordinatorEvent.PlaybackDuckingEnded)
        }
        logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus request granted=$granted")
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        audioFocusGranted = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest
                if (request != null) {
                    audioManager.abandonAudioFocusRequest(request)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
            Unit
        }.onSuccess {
            logger?.log(SerenadaLogLevel.DEBUG, "Audio", "Audio focus abandoned")
        }.onFailure { error ->
            logger?.log(SerenadaLogLevel.WARNING, "Audio", "Failed to abandon audio focus: ${error.message}")
        }
    }

    private fun mapDeviceKind(type: Int): AudioDeviceKind {
        return when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceKind.WiredHeadset
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceKind.Bluetooth(BluetoothProfile.HFP)
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioDeviceKind.Bluetooth(BluetoothProfile.A2DP)
            AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceKind.Bluetooth(BluetoothProfile.BLE)
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDeviceKind.Speakerphone
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDeviceKind.Earpiece
            AudioDeviceInfo.TYPE_AUX_LINE -> AudioDeviceKind.CarAudio
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceKind.Usb
            else -> AudioDeviceKind.Other
        }
    }

    private fun mapDeviceDirection(info: AudioDeviceInfo): AudioDeviceDirection {
        return if (info.isSource && info.isSink) {
            AudioDeviceDirection.BOTH
        } else if (info.isSource) {
            AudioDeviceDirection.INPUT
        } else {
            AudioDeviceDirection.OUTPUT
        }
    }

    private fun mapDeviceInfo(info: AudioDeviceInfo, status: AudioDeviceStatus = AudioDeviceStatus.AVAILABLE): AudioDevice {
        val kind = mapDeviceKind(info.type)
        return AudioDevice(
            id = info.id.toString(),
            displayName = info.productName.toString(),
            kind = kind,
            direction = mapDeviceDirection(info),
            status = status
        )
    }

    private fun AudioDevice.isOutputRoute(): Boolean {
        return direction == AudioDeviceDirection.OUTPUT || direction == AudioDeviceDirection.BOTH
    }

    private fun AudioDeviceKind.isExternalOutputRoute(): Boolean {
        return when (this) {
            is AudioDeviceKind.WiredHeadset,
            is AudioDeviceKind.CarAudio,
            is AudioDeviceKind.Usb,
            is AudioDeviceKind.Other -> true
            is AudioDeviceKind.Bluetooth,
            is AudioDeviceKind.Speakerphone,
            is AudioDeviceKind.Earpiece -> false
        }
    }

    private fun AudioDeviceKind.automaticRouteRank(): Int {
        return when (this) {
            is AudioDeviceKind.WiredHeadset -> 0
            is AudioDeviceKind.CarAudio,
            is AudioDeviceKind.Usb -> 1
            is AudioDeviceKind.Other -> 2
            is AudioDeviceKind.Bluetooth,
            is AudioDeviceKind.Speakerphone,
            is AudioDeviceKind.Earpiece -> 3
        }
    }

    private fun availableOutputDevice(kind: AudioDeviceKind): AudioDevice {
        return _availableDevices.value.firstOrNull { device ->
            device.isOutputRoute() && device.kind == kind
        } ?: AudioDevice(
            id = kind.defaultOutputId(),
            displayName = kind.defaultOutputDisplayName(),
            kind = kind,
            direction = AudioDeviceDirection.OUTPUT,
            status = AudioDeviceStatus.AVAILABLE
        )
    }

    private fun AudioDeviceKind.defaultOutputId(): String {
        return when (this) {
            is AudioDeviceKind.Speakerphone -> "speaker"
            is AudioDeviceKind.Earpiece -> "earpiece"
            is AudioDeviceKind.Bluetooth -> "bluetooth"
            is AudioDeviceKind.WiredHeadset -> "wired"
            is AudioDeviceKind.CarAudio -> "car"
            is AudioDeviceKind.Usb -> "usb"
            is AudioDeviceKind.Other -> "other"
        }
    }

    private fun AudioDeviceKind.defaultOutputDisplayName(): String {
        return when (this) {
            is AudioDeviceKind.Speakerphone -> "Speaker"
            is AudioDeviceKind.Earpiece -> "Earpiece"
            is AudioDeviceKind.Bluetooth -> "Bluetooth"
            is AudioDeviceKind.WiredHeadset -> "Headset"
            is AudioDeviceKind.CarAudio -> "Car audio"
            is AudioDeviceKind.Usb -> "USB audio"
            is AudioDeviceKind.Other -> "Audio"
        }
    }

    private fun deviceKindMatches(actual: AudioDeviceKind, expected: AudioDeviceKind): Boolean {
        return when {
            actual is AudioDeviceKind.Bluetooth && expected is AudioDeviceKind.Bluetooth -> true
            else -> actual == expected
        }
    }

    private fun AudioDeviceInfo.isPublishableRoute(communicationDevices: List<AudioDeviceInfo>): Boolean {
        val direction = mapDeviceDirection(this)
        if (direction == AudioDeviceDirection.INPUT) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mapDeviceKind(type) is AudioDeviceKind.Bluetooth) {
            return communicationDevices.any { it.id == id }
        }
        if (mapDeviceKind(type) !is AudioDeviceKind.Other) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return communicationDevices.any { it.id == id }
    }

    private fun updateDevicesAndRoute() {
        val allDevices = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .toList()
        val communicationDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            emptyList()
        }
        val routeDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            communicationDevices + allDevices
        } else {
            allDevices
        }
        val list = routeDevices
            .distinctBy { it.id }
            .filter { it.isPublishableRoute(communicationDevices) }
            .map { mapDeviceInfo(it, AudioDeviceStatus.AVAILABLE) }

        val activeOutput = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.let { mapDeviceInfo(it, AudioDeviceStatus.ACTIVE) }
        } else {
            if (isSpeakerphoneEnabled()) {
                list.firstOrNull { it.kind is AudioDeviceKind.Speakerphone }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (audioManager.isBluetoothScoOn) {
                list.firstOrNull { it.kind is AudioDeviceKind.Bluetooth }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (allDevices.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) {
                list.firstOrNull { it.kind is AudioDeviceKind.WiredHeadset }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else if (allDevices.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) {
                list.firstOrNull { it.kind is AudioDeviceKind.Usb }?.copy(status = AudioDeviceStatus.ACTIVE)
            } else {
                list.firstOrNull { it.kind is AudioDeviceKind.Earpiece }?.copy(status = AudioDeviceStatus.ACTIVE)
            }
        }

        val activeInput = if (activeOutput != null && (activeOutput.kind is AudioDeviceKind.Bluetooth || activeOutput.kind is AudioDeviceKind.WiredHeadset || activeOutput.kind is AudioDeviceKind.Usb)) {
            list.firstOrNull { it.direction == AudioDeviceDirection.INPUT && it.kind == activeOutput.kind }?.copy(status = AudioDeviceStatus.ACTIVE)
                ?: list.firstOrNull { it.direction == AudioDeviceDirection.INPUT }?.copy(status = AudioDeviceStatus.ACTIVE)
        } else {
            list.firstOrNull { it.direction == AudioDeviceDirection.INPUT && it.kind is AudioDeviceKind.Earpiece }?.copy(status = AudioDeviceStatus.ACTIVE)
                ?: list.firstOrNull { it.direction == AudioDeviceDirection.INPUT }?.copy(status = AudioDeviceStatus.ACTIVE)
        }

        val updatedList = list.map { device ->
            if (device.id == activeOutput?.id || device.id == activeInput?.id) {
                device.copy(status = AudioDeviceStatus.ACTIVE)
            } else {
                device
            }
        }

        _availableDevices.value = updatedList
        _effectiveInputDevice.value = activeInput
        _effectiveOutputDevice.value = activeOutput
        _events.tryEmit(AudioCoordinatorEvent.AvailableDevicesChanged(updatedList))
    }

    private companion object {
        private const val COMMUNICATION_ROUTE_REFRESH_DELAY_MS = 300L
        private const val PLAYBACK_DUCKING_FALLBACK_MS = 3_000L
    }
}
