package app.serenada.android.call

internal fun nextFlipCameraMode(current: LocalCameraMode, compositeAvailable: Boolean): LocalCameraMode {
    return when (current) {
        LocalCameraMode.SELFIE -> LocalCameraMode.WORLD
        LocalCameraMode.WORLD -> if (compositeAvailable) LocalCameraMode.COMPOSITE else LocalCameraMode.SELFIE
        LocalCameraMode.COMPOSITE -> LocalCameraMode.SELFIE
        LocalCameraMode.SCREEN_SHARE -> LocalCameraMode.SELFIE
    }
}
