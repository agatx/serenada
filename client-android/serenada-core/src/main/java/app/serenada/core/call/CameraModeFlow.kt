package app.serenada.core.call

/**
 * Resolve the configured `SerenadaConfig.cameraModes` list into the set of
 * modes this session will allow, in the configured order. `SCREEN_SHARE` is
 * always dropped — screen sharing is controlled separately. Duplicates are
 * removed, preserving the first occurrence. Returning an empty list is
 * valid and signals that video is disabled entirely.
 *
 * `COMPOSITE` is dropped when [compositeAvailable] is false.
 */
internal fun resolveCameraModes(
    configured: List<LocalCameraMode>?,
    compositeAvailable: Boolean = true,
): List<LocalCameraMode> {
    val source = configured ?: app.serenada.core.DEFAULT_CAMERA_MODES
    val seen = mutableSetOf<LocalCameraMode>()
    val result = mutableListOf<LocalCameraMode>()
    for (mode in source) {
        if (mode == LocalCameraMode.SCREEN_SHARE) continue
        if (mode == LocalCameraMode.COMPOSITE && !compositeAvailable) continue
        if (!seen.add(mode)) continue
        result.add(mode)
    }
    return result
}

/**
 * Cycle to the next mode in [modes] after [current], preserving the
 * configured order and optionally skipping `COMPOSITE` when the device can't
 * support it. Returns `null` when the list has one or zero cyclable entries.
 */
internal fun nextCameraMode(
    modes: List<LocalCameraMode>,
    current: LocalCameraMode,
    compositeAvailable: Boolean,
): LocalCameraMode? {
    val cyclable = modes.filter { it != LocalCameraMode.COMPOSITE || compositeAvailable }
    if (cyclable.size <= 1) return null
    val index = cyclable.indexOf(current)
    val nextIndex = if (index == -1) 0 else (index + 1) % cyclable.size
    val next = cyclable[nextIndex]
    return if (next == current) null else next
}
