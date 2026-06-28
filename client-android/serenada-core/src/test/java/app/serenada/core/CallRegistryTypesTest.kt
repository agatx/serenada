package app.serenada.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRegistryTypesTest {

    @Test
    fun extractRoomToken_canonicalCallUrl() {
        assertEquals("tok123", extractRoomToken("https://serenada.app/call/tok123"))
    }

    @Test
    fun extractRoomToken_keysOnCallSegmentNotLastSegment() {
        // Non-canonical trailing segment: key on the segment AFTER `call` (parity
        // with web roomIdentity + iOS DeepLinkParser), not the trailing last
        // segment that lastPathSegment would return.
        assertEquals("tok123", extractRoomToken("https://serenada.app/call/tok123/extra"))
    }

    @Test
    fun extractRoomToken_fallsBackToLastSegmentWithoutCallSegment() {
        assertEquals("xyz", extractRoomToken("https://example.com/rooms/xyz"))
    }

    @Test
    fun canonicalRoomId_urlAndBareIdAgreeForTrailingSegmentUrl() {
        // The dedup key must match the room the single-call join would connect to,
        // even for a non-canonical trailing-segment URL.
        assertEquals(
            canonicalRoomId(RoomRef.Id("tok123")),
            canonicalRoomId(RoomRef.Url("https://serenada.app/call/tok123/extra")),
        )
    }
}
