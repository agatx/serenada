package app.serenada.core.call

enum class LocalCameraMode {
    SELFIE,
    WORLD,
    COMPOSITE,
    SCREEN_SHARE;

    val isContentMode: Boolean get() = this == WORLD || this == COMPOSITE
}

object ContentTypeWire {
    const val SCREEN_SHARE = "screenShare"
    const val WORLD_CAMERA = "worldCamera"
    const val COMPOSITE_CAMERA = "compositeCamera"
}
