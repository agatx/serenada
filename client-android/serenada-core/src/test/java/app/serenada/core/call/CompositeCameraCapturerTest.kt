package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeCameraCapturerTest {

    @Test
    fun mirrorDisplayXInCrop_flipsCoordinatesAcrossCropBounds() {
        assertEquals(90f, mirrorDisplayXInCrop(displayX = 10f, cropLeft = 10, cropSize = 81), 0.0001f)
        assertEquals(10f, mirrorDisplayXInCrop(displayX = 90f, cropLeft = 10, cropSize = 81), 0.0001f)
    }

    @Test
    fun mirrorDisplayXInCrop_preservesSubpixelDistanceFromOppositeEdge() {
        assertEquals(
            75.75f,
            mirrorDisplayXInCrop(displayX = 24.25f, cropLeft = 10, cropSize = 81),
            0.0001f
        )
    }
}
