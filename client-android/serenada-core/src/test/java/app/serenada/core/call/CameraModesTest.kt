package app.serenada.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraModesTest {
    @Test
    fun resolveDefaultsToAllModesWhenNull() {
        assertEquals(
            listOf(LocalCameraMode.SELFIE, LocalCameraMode.WORLD, LocalCameraMode.COMPOSITE),
            resolveCameraModes(null),
        )
    }

    @Test
    fun resolvePreservesConfiguredOrder() {
        assertEquals(
            listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
            resolveCameraModes(listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE)),
        )
    }

    @Test
    fun resolveDropsScreenShare() {
        assertEquals(
            listOf(LocalCameraMode.SELFIE, LocalCameraMode.WORLD),
            resolveCameraModes(listOf(LocalCameraMode.SELFIE, LocalCameraMode.SCREEN_SHARE, LocalCameraMode.WORLD)),
        )
    }

    @Test
    fun resolveKeepsEmptyListEmpty() {
        assertEquals(emptyList<LocalCameraMode>(), resolveCameraModes(emptyList()))
    }

    @Test
    fun resolveDeduplicates() {
        assertEquals(
            listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
            resolveCameraModes(listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE, LocalCameraMode.WORLD)),
        )
    }

    @Test
    fun resolveDropsCompositeWhenUnsupported() {
        assertEquals(
            listOf(LocalCameraMode.SELFIE, LocalCameraMode.WORLD),
            resolveCameraModes(
                listOf(LocalCameraMode.SELFIE, LocalCameraMode.COMPOSITE, LocalCameraMode.WORLD),
                compositeAvailable = false,
            ),
        )
    }

    @Test
    fun nextCameraModeNilForSingletonList() {
        assertNull(
            nextCameraMode(
                modes = listOf(LocalCameraMode.SELFIE),
                current = LocalCameraMode.SELFIE,
                compositeAvailable = true,
            )
        )
    }

    @Test
    fun nextCameraModeCyclesInConfiguredOrder() {
        assertEquals(
            LocalCameraMode.SELFIE,
            nextCameraMode(
                modes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
                current = LocalCameraMode.WORLD,
                compositeAvailable = true,
            ),
        )
        assertEquals(
            LocalCameraMode.WORLD,
            nextCameraMode(
                modes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
                current = LocalCameraMode.SELFIE,
                compositeAvailable = true,
            ),
        )
    }

    @Test
    fun nextCameraModeSkipsCompositeWhenDeviceLacksIt() {
        assertEquals(
            LocalCameraMode.SELFIE,
            nextCameraMode(
                modes = listOf(LocalCameraMode.SELFIE, LocalCameraMode.WORLD, LocalCameraMode.COMPOSITE),
                current = LocalCameraMode.WORLD,
                compositeAvailable = false,
            ),
        )
    }

    @Test
    fun nextCameraModeFallsBackToFirstWhenCurrentMissing() {
        assertEquals(
            LocalCameraMode.WORLD,
            nextCameraMode(
                modes = listOf(LocalCameraMode.WORLD, LocalCameraMode.SELFIE),
                current = LocalCameraMode.COMPOSITE,
                compositeAvailable = true,
            ),
        )
    }
}
