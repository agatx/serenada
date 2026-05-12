package app.serenada.core

import android.os.Handler
import android.os.Looper
import app.serenada.core.call.FrameSnapshotCapture
import app.serenada.core.fakes.TestSessionFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.webrtc.VideoSink

/**
 * Verifies the early-return contract of [SerenadaSession.captureSnapshot]:
 * the SDK refuses to attach a sink (and reports `StreamNotActive`) when the
 * chosen stream's track is missing or disabled, never paying the timeout cost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotCaptureTest {

    private lateinit var factory: TestSessionFactory

    @Before fun setUp() { factory = TestSessionFactory() }
    @After fun tearDown() { factory.tearDown() }

    @Test
    fun `captureSnapshot local throws StreamNotActive before joining`() = runBlocking {
        // Default phase after construction is Idle/Joining — capture should refuse.
        val error = runCatching {
            factory.session.captureSnapshot(SnapshotSource.Local)
        }.exceptionOrNull()
        assertTrue("Expected StreamNotActive but got $error", error is SnapshotError.StreamNotActive)
        assertEquals(0, factory.fakeMedia.attachLocalSinkCalls.size)
    }

    @Test
    fun `captureSnapshot local throws StreamNotActive when video disabled`() = runBlocking {
        val factory = TestSessionFactory(defaultVideoEnabled = false)
        try {
            factory.advanceToInCallWithTurn(localCid = "me", remoteCid = "peer")
            val error = runCatching {
                factory.session.captureSnapshot(SnapshotSource.Local)
            }.exceptionOrNull()
            assertTrue(
                "Expected StreamNotActive but got $error",
                error is SnapshotError.StreamNotActive,
            )
            assertEquals(
                "Should not attach sink when video is off",
                0,
                factory.fakeMedia.attachLocalSinkCalls.size,
            )
        } finally {
            factory.tearDown()
        }
    }

    @Test
    fun `captureSnapshot remote throws StreamNotActive when slot missing`() = runBlocking {
        val error = runCatching {
            factory.session.captureSnapshot(SnapshotSource.Remote("never-joined"))
        }.exceptionOrNull()
        assertTrue("Expected StreamNotActive but got $error", error is SnapshotError.StreamNotActive)
    }

    @Test
    fun `captureSnapshot remote throws StreamNotActive when remote video off`() = runBlocking {
        factory.advanceToInCallWithTurn(localCid = "me", remoteCid = "peer")
        val slot = factory.fakeMedia.fakeSlots.getValue("peer")
        // Default override is false; assertions below confirm that drives StreamNotActive.
        assertEquals(false, slot.isRemoteVideoTrackEnabled())

        val error = runCatching {
            factory.session.captureSnapshot(SnapshotSource.Remote("peer"))
        }.exceptionOrNull()
        assertTrue("Expected StreamNotActive but got $error", error is SnapshotError.StreamNotActive)
        assertEquals(
            "Should not attach sink when remote video is off",
            0,
            slot.attachRemoteSinkCalls.size,
        )
    }

    @Test
    fun `SnapshotError variants compare correctly`() {
        assertEquals(SnapshotError.StreamNotActive, SnapshotError.StreamNotActive)
        assertEquals(SnapshotError.NoVideoTrack, SnapshotError.NoVideoTrack)
        assertEquals(SnapshotError.CaptureTimeout, SnapshotError.CaptureTimeout)
        assertNotEquals(SnapshotError.StreamNotActive, SnapshotError.CaptureTimeout)

        assertEquals(SnapshotError.CaptureFailed("x"), SnapshotError.CaptureFailed("x"))
        assertNotEquals(SnapshotError.CaptureFailed("x"), SnapshotError.CaptureFailed("y"))
    }

    @Test
    fun `SnapshotSource variants compare correctly`() {
        val local: SnapshotSource = SnapshotSource.Local
        val remoteA: SnapshotSource = SnapshotSource.Remote("a")
        val remoteB: SnapshotSource = SnapshotSource.Remote("b")
        assertEquals(SnapshotSource.Local, local)
        assertEquals(SnapshotSource.Remote("a"), remoteA)
        assertNotEquals(remoteA, remoteB)
        assertNotEquals(local, remoteA)
    }

    @Test
    fun `FrameSnapshotCapture times out and detaches sink exactly once`() = runBlocking {
        val attached = AtomicInteger(0)
        val detached = AtomicInteger(0)
        val handler = Handler(Looper.getMainLooper())

        val capture = FrameSnapshotCapture(
            handler = handler,
            source = SnapshotSource.Local,
            attachSink = { attached.incrementAndGet() },
            detachSink = { detached.incrementAndGet() },
            timeoutMs = 50L,
        )

        val deferred = async(Dispatchers.Unconfined) {
            runCatching { capture.capture() }
        }

        // Advance Robolectric's main looper past the timeout so the
        // postDelayed timeout runnable fires and the capture finishes.
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

        val outcome = deferred.await()
        ShadowLooper.idleMainLooper()

        val error = outcome.exceptionOrNull()
        assertTrue("Expected CaptureTimeout but got $error", error is SnapshotError.CaptureTimeout)
        assertEquals(1, attached.get())
        assertEquals(1, detached.get())
    }

    @Test
    fun `SnapshotResult equality covers all fields`() {
        val a = SnapshotResult(
            jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            width = 1280,
            height = 720,
            timestampMs = 100L,
            source = SnapshotSource.Local,
        )
        val b = SnapshotResult(
            jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()),
            width = 1280,
            height = 720,
            timestampMs = 100L,
            source = SnapshotSource.Local,
        )
        val different = a.copy(width = 640)
        assertEquals(a, b)
        assertNotEquals(a, different)
    }
}
