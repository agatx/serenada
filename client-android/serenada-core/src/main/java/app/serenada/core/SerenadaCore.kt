package app.serenada.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.serenada.core.call.CallMediaRole
import app.serenada.core.network.CoreApiClient
import java.util.IdentityHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient

/**
 * Main entry point for the Serenada SDK.
 *
 * Create an instance with a [SerenadaConfig], then use [join] to start a call session
 * or [createRoom] to create a new room.
 */
class SerenadaCore(
    /** SDK configuration. */
    val config: SerenadaConfig,
    private val context: Context,
) {
    /** Callback delegate for session lifecycle events. */
    var delegate: SerenadaCoreDelegate? = null

    /** Logger instance for debug output. */
    var logger: SerenadaLogger? = null

    private val okHttpClient = OkHttpClient.Builder().build()
    private val apiClient = CoreApiClient(okHttpClient)
    private val resolvedConfig = resolveSerenadaConfig(config)
    internal val recoveryStorage = RecoveryStorage(context)

    /**
     * Returns a recoverable session if the previous process ended abruptly
     * (kill, OS LMK, crash) while a call was active and the persisted
     * reconnect token is still within its TTL. Host apps should call this on
     * launch and surface a "Rejoin call?" prompt — calling [join] with the
     * returned `roomId` reattaches under the same CID.
     *
     * Returns `null` when there is nothing to recover.
     */
    fun getRecoverableSession(): RecoveryRecord? {
        assertMainThread()
        return recoveryStorage.load()
    }

    /**
     * Drops any persisted recovery record. Call this when the user
     * explicitly declines the rejoin prompt so subsequent launches do not
     * keep offering the same dead session.
     */
    fun discardRecoverableSession() {
        assertMainThread()
        recoveryStorage.clear()
    }

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SerenadaCore APIs must be called on the main thread"
        }
    }

    /**
     * Join a call using a full URL (e.g., "https://serenada.app/call/ABC123").
     *
     * @param peerId optional host-supplied stable identity for this user (distinct from
     *   the per-call client ID). Surfaced on remote participants so the call UI can
     *   resolve avatars via [SerenadaCallFlowConfig.avatarProvider].
     */
    fun join(url: String, displayName: String? = null, peerId: String? = null): SerenadaSession {
        assertMainThread()
        val resolved = resolveRoomUrl(url)
        val roomId = resolved?.roomId ?: url
        val sessionConfig = sessionConfigFor(resolved?.serverHost)
        val signalingProvider = createSignalingProvider(sessionConfig, roomId)
        val session = buildSessionReleasingProviderOnFailure(signalingProvider) {
            SerenadaSession(
                roomId = roomId,
                roomUrl = resolved?.roomUrl ?: url,
                config = sessionConfig,
                context = context,
                delegate = { delegate },
                okHttpClient = okHttpClient,
                initialSignalingProvider = signalingProvider,
                logger = logger,
                displayName = displayName,
                peerId = peerId,
                // Public single-call join always foregrounds and routes through the
                // process-wide arbiter (mode DIRECT). A second concurrent direct join
                // fails fast with ForegroundLeaseUnavailable (contract §2).
                acquireForegroundLease = true,
            )
        }
        session.start()
        return session
    }

    /**
     * Join a call using a room ID.
     *
     * @param peerId optional host-supplied stable identity — see the URL [join] overload.
     */
    fun join(
        roomId: String,
        serverHost: String? = resolvedConfig.serverHost,
        displayName: String? = null,
        peerId: String? = null,
    ): SerenadaSession {
        assertMainThread()
        val sessionConfig = sessionConfigFor(serverHost)
        val roomUrl = resolvedConfig.serverHost?.let { buildRoomUrl(serverHost ?: it, roomId) }
        val signalingProvider = createSignalingProvider(sessionConfig, roomId)
        val session = buildSessionReleasingProviderOnFailure(signalingProvider) {
            SerenadaSession(
                roomId = roomId,
                roomUrl = roomUrl,
                config = sessionConfig,
                context = context,
                delegate = { delegate },
                okHttpClient = okHttpClient,
                initialSignalingProvider = signalingProvider,
                logger = logger,
                displayName = displayName,
                peerId = peerId,
                // Public single-call join always foregrounds via the arbiter (DIRECT).
                acquireForegroundLease = true,
            )
        }
        session.start()
        return session
    }

    /**
     * Registry-internal join with an explicit initial media role (multi-call
     * session, Phase 2/3). The public [join] overloads always foreground and
     * route through the arbiter in DIRECT mode; this is the seam the (Phase 3)
     * [SerenadaCallRegistry] uses to create a HELD call that owns no capture and
     * holds NO arbiter lease (`acquireForegroundLease = false` — the registry owns
     * the lease itself). NOT part of the public single-call surface.
     *
     * @param room how the host named the room (URL or bare id).
     * @param initialMediaRole HELD for a registry-created held call.
     * @param displayName/peerId forwarded to the session as on [join].
     */
    internal fun joinInternal(
        room: RoomRef,
        initialMediaRole: CallMediaRole,
        displayName: String? = null,
        peerId: String? = null,
    ): SerenadaSession {
        assertMainThread()
        val resolvedRoomId: String
        val resolvedRoomUrl: String?
        val serverHostForConfig: String?
        when (room) {
            is RoomRef.Url -> {
                val resolved = resolveRoomUrl(room.url)
                resolvedRoomId = resolved?.roomId ?: room.url
                resolvedRoomUrl = resolved?.roomUrl ?: room.url
                serverHostForConfig = resolved?.serverHost
            }
            is RoomRef.Id -> {
                resolvedRoomId = room.roomId
                serverHostForConfig = room.serverHost ?: resolvedConfig.serverHost
                resolvedRoomUrl = serverHostForConfig?.let { host -> buildRoomUrl(host, room.roomId) }
            }
        }
        val sessionConfig = sessionConfigFor(serverHostForConfig)
        val signalingProvider = createSignalingProvider(sessionConfig, resolvedRoomId)
        val session = buildSessionReleasingProviderOnFailure(signalingProvider) {
            SerenadaSession(
                roomId = resolvedRoomId,
                roomUrl = resolvedRoomUrl,
                config = sessionConfig,
                context = context,
                delegate = { delegate },
                okHttpClient = okHttpClient,
                initialSignalingProvider = signalingProvider,
                logger = logger,
                displayName = displayName,
                peerId = peerId,
                initialMediaRole = initialMediaRole,
                // The registry owns the foreground lease; the session never self-acquires.
                acquireForegroundLease = false,
            )
        }
        session.start()
        return session
    }

    /**
     * Create a new room. Returns the room URL and ID. Call [join] to start the call.
     */
    suspend fun createRoom(): CreateRoomResult {
        assertMainThread()
        val serverHost = requireServerHost(config)
        val roomId = suspendCancellableCoroutine<String> { continuation ->
            apiClient.createRoomId(serverHost) { result ->
                result
                    .onSuccess { resolvedRoomId ->
                        continuation.resume(resolvedRoomId)
                    }
                    .onFailure { error ->
                        continuation.resumeWithException(error)
                    }
            }
        }

        val roomUrl = buildRoomUrl(serverHost, roomId)
        return CreateRoomResult(roomId = roomId, roomUrl = roomUrl)
    }

    /**
     * Create a room ID without starting a session.
     * Use this when you only need a room ID (e.g., for invite links).
     */
    suspend fun createRoomId(): String {
        assertMainThread()
        val serverHost = requireServerHost(config)
        return suspendCancellableCoroutine { continuation ->
            apiClient.createRoomId(serverHost) { result ->
                result
                    .onSuccess { continuation.resume(it) }
                    .onFailure { continuation.resumeWithException(it) }
            }
        }
    }

    private fun resolveRoomUrl(url: String): ResolvedRoomUrl? {
        val trimmed = url.trim()
        if (!trimmed.contains("/")) return null
        return try {
            val uri = android.net.Uri.parse(trimmed)
            // Shared token extraction (single source of truth with the registry's
            // canonicalRoomId dedup key) so the two parsers can never drift.
            val roomId = extractRoomToken(trimmed) ?: return null
            val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
            val scheme = uri.scheme?.takeIf { it.isNotBlank() }
                ?: if (isLocalHost(authority)) "http" else "https"
            ResolvedRoomUrl(
                roomId = roomId,
                serverHost = authority,
                roomUrl = "$scheme://$authority/call/$roomId"
            )
        } catch (_: Exception) {
            val roomId = trimmed.split("/").lastOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val fallbackHost = resolvedConfig.serverHost ?: return null
            ResolvedRoomUrl(
                roomId = roomId,
                serverHost = fallbackHost,
                roomUrl = buildRoomUrl(fallbackHost, roomId)
            )
        }
    }

    private fun buildRoomUrl(serverHost: String, roomId: String): String {
        val scheme = if (isLocalHost(serverHost)) "http" else "https"
        return "$scheme://$serverHost/call/$roomId"
    }

    private fun isLocalHost(serverHost: String): Boolean {
        val normalized = serverHost.trim().lowercase()
        return normalized.startsWith("localhost") ||
            normalized.startsWith("127.") ||
            normalized.startsWith("10.0.2.2")
    }

    private data class ResolvedRoomUrl(
        val roomId: String,
        val serverHost: String,
        val roomUrl: String,
    )

    private fun sessionConfigFor(serverHostOverride: String?): SerenadaConfig {
        if (resolvedConfig.serverHost == null) {
            return config
        }
        val serverHost = serverHostOverride?.trim()?.takeIf { it.isNotEmpty() } ?: resolvedConfig.serverHost
        return config.copy(
            serverHost = serverHost,
            signalingProvider = null,
            multiSessionSignalingProvider = null,
        )
    }

    /**
     * Resolve the signaling channel for one session (contract §"Custom provider").
     * Server mode builds a fresh [SerenadaServerProvider]; a
     * [MultiSessionSignalingProvider] vends a per-session channel via
     * [MultiSessionSignalingProvider.openSession]; a single-session v1 provider is
     * bound through the liveness guard. Internal so the registry seam and tests can
     * exercise the real resolution path.
     */
    internal fun createSignalingProvider(sessionConfig: SerenadaConfig, roomId: String): SignalingProvider {
        val resolved = resolveSerenadaConfig(sessionConfig)
        val serverHost = resolved.serverHost
        if (serverHost != null) {
            return SerenadaServerProvider(
                serverHost = serverHost,
                handler = Handler(Looper.getMainLooper()),
                okHttpClient = okHttpClient,
                apiClient = apiClient,
                transports = sessionConfig.transports,
                logger = logger,
            )
        }
        resolved.multiSessionSignalingProvider?.let { multi ->
            return multi.openSession(roomId)
        }
        val provider = resolved.signalingProvider
            ?: throw IllegalStateException("Provide exactly one of serverHost or signalingProvider")
        return bindSingleSessionProvider(provider)
    }

    /**
     * Bind [provider] to a new session, enforcing the single-session invariant: a
     * second concurrent bind throws [SingleSessionProviderInUseException] instead of
     * silently reusing the object (which would cross-wire the two sessions). The
     * returned channel releases the bind on its [SignalingProvider.disconnect] — the
     * call every session teardown makes — so sequential reuse keeps working. The bind
     * map is process-wide (see [boundV1Providers]), so the guard holds across
     * independent [SerenadaCore] instances that share one v1 provider object too.
     *
     * The map records the OWNING channel per provider object, not just membership, so a
     * retiring channel only ever clears its OWN bind: a channel that already released
     * (terminal reset) and then gets a late [SignalingProvider.disconnect] (host close)
     * must not evict the entry a NEWER channel now owns for the same shared v1 object.
     */
    private fun bindSingleSessionProvider(provider: SignalingProvider): SignalingProvider {
        synchronized(boundV1Providers) {
            if (boundV1Providers.containsKey(provider)) {
                throw SingleSessionProviderInUseException()
            }
            val channel = SingleSessionV1Channel(provider) { owner ->
                synchronized(boundV1Providers) {
                    // Ownership-scoped one-shot: retire this channel's bind exactly
                    // once, and only while it still owns the provider object. Returns
                    // true on the first retire (so the channel forwards the underlying
                    // disconnect) and false afterwards — or once a newer channel owns
                    // the object — so a rebound provider is never torn down.
                    if (boundV1Providers[provider] === owner) {
                        boundV1Providers.remove(provider)
                        true
                    } else {
                        false
                    }
                }
            }
            boundV1Providers[provider] = channel
            return channel
        }
    }

    /**
     * Construct a session whose signaling [provider] was already created (and, for a
     * v1 provider, whose liveness bind was already claimed by [createSignalingProvider]).
     * If [construct] throws, no session exists to run the teardown that releases the
     * bind, so release it here before rethrowing — otherwise the v1 object stays bound
     * forever and every later join on any core fails [SingleSessionProviderInUseException].
     * [SignalingProvider.disconnect] on the vended v1 channel frees the bind; on a fresh
     * server / multi-session channel it is a harmless disconnect of a never-connected
     * provider. Covers both the direct [join] paths and the registry's [joinInternal].
     */
    internal fun buildSessionReleasingProviderOnFailure(
        provider: SignalingProvider,
        construct: () -> SerenadaSession,
    ): SerenadaSession {
        return try {
            construct()
        } catch (e: Throwable) {
            runCatching { provider.disconnect() }
            throw e
        }
    }

    companion object {
        const val VERSION = "0.9.1"

        /**
         * Process-wide map of single-session (v1) [SignalingProvider] objects currently
         * bound by a live session to the [SingleSessionV1Channel] that owns the bind,
         * keyed by object IDENTITY (contract §"v1 liveness guard"). The guard is per
         * provider OBJECT, not per [SerenadaCore]: two cores configured with the SAME v1
         * provider would each keep an empty per-instance guard and both bind it,
         * overwriting the shared [SignalingProvider.listener] and cross-wiring the two
         * sessions. Keying here — above any single core — makes at most one session hold
         * a given v1 object across the whole process. Recording the owning channel (not
         * bare membership) lets a retiring channel release only its OWN bind, never one a
         * newer channel has since claimed for the same shared object. Server mode and
         * [MultiSessionSignalingProvider] mode never touch this (both mint a fresh channel
         * per session). Guarded by its own monitor so binds/releases stay consistent
         * regardless of the calling thread.
         */
        private val boundV1Providers: IdentityHashMap<SignalingProvider, SingleSessionV1Channel> =
            IdentityHashMap()
    }
}

/**
 * Session-scoped wrapper around a single-session (v1) [SignalingProvider]. Delegates
 * every operation to the underlying provider but is fully ONE-SHOT: the first
 * [disconnect] retires the channel and only then touches the shared provider;
 * everything after that is inert.
 *
 * On that first (and only) retire it (1) releases the [SerenadaCore] liveness bind via
 * [retire] — ownership-scoped and synchronized with the companion lock, so it never
 * evicts a bind a newer channel has since claimed for the same shared v1 object — then
 * (2) detaches the session's listener and disconnects the underlying provider. Any
 * later [disconnect]/[listener] mutation is dropped, so a terminal-error reset followed
 * by a host `close()` on the same dead session can never tear down (or clear the
 * listener of) a live session that has meanwhile rebound the shared provider object.
 */
private class SingleSessionV1Channel(
    private val provider: SignalingProvider,
    // Retires this channel's liveness bind. Returns true on the first call (this
    // channel still owned the bind), false on any later call — the one-shot latch.
    private val retire: (SingleSessionV1Channel) -> Boolean,
) : SignalingProvider by provider {
    private var retired = false

    override var listener: SignalingProvider.Listener?
        get() = provider.listener
        set(value) {
            if (!retired) provider.listener = value
        }

    override fun disconnect() {
        // Ownership-scoped one-shot: retire the bind under the companion lock. If this
        // channel had already released (or a newer channel now owns the shared
        // provider), do NOT forward to the underlying provider — that would tear down
        // whoever owns it now.
        if (!retire(this)) return
        retired = true
        provider.listener = null
        provider.disconnect()
    }
}

data class CreateRoomResult(
    val roomId: String,
    val roomUrl: String,
)
