package app.serenada.android.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeFlowTest {

    @Test
    fun nextFlipCameraMode_cyclesThroughCompositeWhenAvailable() {
        assertEquals(
            LocalCameraMode.WORLD,
            nextFlipCameraMode(LocalCameraMode.SELFIE, compositeAvailable = true)
        )
        assertEquals(
            LocalCameraMode.COMPOSITE,
            nextFlipCameraMode(LocalCameraMode.WORLD, compositeAvailable = true)
        )
        assertEquals(
            LocalCameraMode.SELFIE,
            nextFlipCameraMode(LocalCameraMode.COMPOSITE, compositeAvailable = true)
        )
    }

    @Test
    fun nextFlipCameraMode_skipsCompositeWhenUnavailable() {
        assertEquals(
            LocalCameraMode.SELFIE,
            nextFlipCameraMode(LocalCameraMode.WORLD, compositeAvailable = false)
        )
    }
}
