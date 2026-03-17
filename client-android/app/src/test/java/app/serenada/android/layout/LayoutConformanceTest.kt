package app.serenada.android.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val STRICT_TOLERANCE = 0.005

/**
 * Cross-platform layout conformance test. Reads the shared fixture file and validates
 * that the Kotlin layout engine produces the same results as the TypeScript reference.
 *
 * Uses manual JSON parsing to avoid Android's org.json which is not available in local unit tests.
 */
@RunWith(Parameterized::class)
class LayoutConformanceTest(
    private val caseId: String,
    private val caseJson: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val text = LayoutConformanceTest::class.java
                .getResourceAsStream("/fixtures/layout_conformance_v1.json")
                ?.bufferedReader()?.readText()
                ?: error("Fixture file not found")

            // Simple extraction of case objects from the JSON array
            val casesStart = text.indexOf("\"cases\"") + "\"cases\"".length
            val arrayStart = text.indexOf('[', casesStart)
            val arrayEnd = text.lastIndexOf(']')
            val casesArrayContent = text.substring(arrayStart + 1, arrayEnd)

            // Split by top-level objects in the array
            val caseStrings = splitTopLevelObjects(casesArrayContent)

            return caseStrings.map { caseStr ->
                val id = extractString(caseStr, "id")
                arrayOf(id, caseStr)
            }
        }

        private fun splitTopLevelObjects(content: String): List<String> {
            val objects = mutableListOf<String>()
            var depth = 0
            var start = -1
            for (i in content.indices) {
                when (content[i]) {
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start >= 0) {
                            objects.add(content.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return objects
        }

        private fun extractString(json: String, key: String): String {
            val keyPattern = "\"$key\""
            val keyIdx = json.indexOf(keyPattern)
            if (keyIdx < 0) error("Key '$key' not found in JSON")
            val colonIdx = json.indexOf(':', keyIdx + keyPattern.length)
            val valueStart = json.indexOf('"', colonIdx + 1) + 1
            val valueEnd = json.indexOf('"', valueStart)
            return json.substring(valueStart, valueEnd)
        }
    }

    @Test
    fun conformance() {
        val scene = parseScene(extractObject(caseJson, "scene"))
        val expected = extractObject(caseJson, "expected")
        val result = computeLayout(scene)

        // Mode
        val expectedMode = extractString(expected, "mode")
        assertEquals("mode mismatch for $caseId", expectedMode, result.mode.name.lowercase())

        // Tile count
        val expectedTileCount = extractInt(expected, "tileCount")
        assertEquals("tile count mismatch for $caseId", expectedTileCount, result.tiles.size)

        // Tile frames
        val expectedTiles = extractArray(expected, "tiles")
        for (i in expectedTiles.indices) {
            val et = expectedTiles[i]
            val at = result.tiles[i]

            assertEquals("tile[$i] id for $caseId", extractString(et, "id"), at.id)
            val actualType = when (at.type) {
                OccupantType.PARTICIPANT -> "participant"
                OccupantType.CONTENT_SOURCE -> "contentSource"
            }
            assertEquals("tile[$i] type for $caseId", extractString(et, "type"), actualType)
            assertEquals("tile[$i] fit for $caseId", extractString(et, "fit"), at.fit.name.lowercase())

            val expectedFrame = extractObject(et, "normalizedFrame")
            val actualFrame = normalizeFrame(at.frame, scene.viewportWidth, scene.viewportHeight)
            assertFrameClose(actualFrame, expectedFrame, STRICT_TOLERANCE, "$caseId tile[$i]")
        }

        // Local PIP
        val localPipJson = extractObjectOrNull(expected, "localPip")
        if (localPipJson == null) {
            assertNull("localPip should be null for $caseId", result.localPip)
        } else {
            assertNotNull("localPip should not be null for $caseId", result.localPip)
            val pip = result.localPip!!

            assertEquals("pip participantId for $caseId",
                extractString(localPipJson, "participantId"), pip.participantId)
            assertEquals("pip anchor for $caseId",
                extractString(localPipJson, "anchor"), anchorToString(pip.anchor))

            val expectedPipFrame = extractObject(localPipJson, "normalizedFrame")
            val actualPipFrame = normalizeFrame(pip.frame, scene.viewportWidth, scene.viewportHeight)
            assertFrameClose(actualPipFrame, expectedPipFrame, STRICT_TOLERANCE, "$caseId pip")
        }
    }

    // --- Parsing helpers ---

    private fun parseScene(json: String): CallScene {
        val participantsArray = extractArray(json, "participants")
        val participants = participantsArray.map { p ->
            SceneParticipant(
                id = extractString(p, "id"),
                role = if (extractString(p, "role") == "local") ParticipantRole.LOCAL else ParticipantRole.REMOTE,
                videoEnabled = extractBoolean(p, "videoEnabled"),
                videoAspectRatio = extractFloatOrNull(p, "videoAspectRatio"),
            )
        }

        val insetsJson = extractObject(json, "safeAreaInsets")
        val userPrefsJson = extractObjectOrNull(json, "userPrefs")

        val contentSourceJson = extractObjectOrNull(json, "contentSource")
        val contentSource = contentSourceJson?.let { cs ->
            ContentSource(
                type = ContentType.fromWire(extractString(cs, "type")),
                ownerParticipantId = extractString(cs, "ownerParticipantId"),
                aspectRatio = extractFloatOrNull(cs, "aspectRatio"),
            )
        }

        return CallScene(
            viewportWidth = extractFloat(json, "viewportWidth"),
            viewportHeight = extractFloat(json, "viewportHeight"),
            safeAreaInsets = Insets(
                top = extractFloat(insetsJson, "top"),
                bottom = extractFloat(insetsJson, "bottom"),
                left = extractFloat(insetsJson, "left"),
                right = extractFloat(insetsJson, "right"),
            ),
            participants = participants,
            localParticipantId = extractString(json, "localParticipantId"),
            activeSpeakerId = extractStringOrNull(json, "activeSpeakerId"),
            pinnedParticipantId = extractStringOrNull(json, "pinnedParticipantId"),
            contentSource = contentSource,
            userPrefs = UserLayoutPrefs(
                swappedLocalAndRemote = userPrefsJson?.let { extractBoolean(it, "swappedLocalAndRemote") } ?: false,
                dominantFit = if (userPrefsJson?.let { extractString(it, "dominantFit") } == "contain") FitMode.CONTAIN else FitMode.COVER,
            ),
        )
    }

    private data class NormalizedFrame(val x: Double, val y: Double, val width: Double, val height: Double)

    private fun normalizeFrame(frame: LayoutRect, viewportWidth: Float, viewportHeight: Float): NormalizedFrame =
        NormalizedFrame(
            x = frame.x.toDouble() / viewportWidth,
            y = frame.y.toDouble() / viewportHeight,
            width = frame.width.toDouble() / viewportWidth,
            height = frame.height.toDouble() / viewportHeight,
        )

    private fun assertFrameClose(actual: NormalizedFrame, expectedJson: String, tolerance: Double, label: String) {
        val ex = extractFloat(expectedJson, "x").toDouble()
        val ey = extractFloat(expectedJson, "y").toDouble()
        val ew = extractFloat(expectedJson, "width").toDouble()
        val eh = extractFloat(expectedJson, "height").toDouble()

        assertTrue("$label x: ${actual.x} vs $ex", kotlin.math.abs(actual.x - ex) <= tolerance)
        assertTrue("$label y: ${actual.y} vs $ey", kotlin.math.abs(actual.y - ey) <= tolerance)
        assertTrue("$label width: ${actual.width} vs $ew", kotlin.math.abs(actual.width - ew) <= tolerance)
        assertTrue("$label height: ${actual.height} vs $eh", kotlin.math.abs(actual.height - eh) <= tolerance)
    }

    private fun anchorToString(anchor: Anchor): String = when (anchor) {
        Anchor.TOP_LEFT -> "topLeft"
        Anchor.TOP_RIGHT -> "topRight"
        Anchor.BOTTOM_LEFT -> "bottomLeft"
        Anchor.BOTTOM_RIGHT -> "bottomRight"
    }

    // --- Minimal JSON extraction (no Android JSON dependency) ---

    private fun extractObject(json: String, key: String): String {
        val keyPattern = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyIdx = json.indexOf(keyPattern, searchFrom)
            if (keyIdx < 0) error("Key '$key' not found")
            val colonIdx = json.indexOf(':', keyIdx + keyPattern.length)
            val afterColon = json.substring(colonIdx + 1).trimStart()
            if (afterColon.startsWith("{")) {
                val objStart = json.indexOf('{', colonIdx)
                return extractBalanced(json, objStart, '{', '}')
            }
            searchFrom = keyIdx + keyPattern.length
        }
    }

    private fun extractObjectOrNull(json: String, key: String): String? {
        val keyPattern = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyIdx = json.indexOf(keyPattern, searchFrom)
            if (keyIdx < 0) return null
            val colonIdx = json.indexOf(':', keyIdx + keyPattern.length)
            val afterColon = json.substring(colonIdx + 1).trimStart()
            if (afterColon.startsWith("null")) return null
            if (afterColon.startsWith("{")) {
                val objStart = json.indexOf('{', colonIdx)
                return extractBalanced(json, objStart, '{', '}')
            }
            searchFrom = keyIdx + keyPattern.length
        }
    }

    private fun extractArray(json: String, key: String): List<String> {
        val keyPattern = "\"$key\""
        val keyIdx = json.indexOf(keyPattern)
        if (keyIdx < 0) return emptyList()
        val colonIdx = json.indexOf(':', keyIdx + keyPattern.length)
        val arrStart = json.indexOf('[', colonIdx)
        val arrContent = extractBalanced(json, arrStart, '[', ']')
        val inner = arrContent.substring(1, arrContent.length - 1)
        return Companion.splitTopLevelObjects(inner)
    }

    private fun extractBalanced(json: String, start: Int, open: Char, close: Char): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until json.length) {
            val c = json[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == open) depth++
            if (c == close) depth--
            if (depth == 0) return json.substring(start, i + 1)
        }
        error("Unbalanced $open/$close from position $start")
    }

    private fun extractFloat(json: String, key: String): Float {
        val raw = extractRawValue(json, key)
        return raw.toFloat()
    }

    private fun extractFloatOrNull(json: String, key: String): Float? {
        val raw = extractRawValue(json, key)
        if (raw == "null") return null
        return raw.toFloatOrNull()
    }

    private fun extractInt(json: String, key: String): Int {
        return extractRawValue(json, key).toInt()
    }

    private fun extractBoolean(json: String, key: String): Boolean {
        return extractRawValue(json, key).toBoolean()
    }

    private fun extractStringOrNull(json: String, key: String): String? {
        val raw = extractRawValue(json, key)
        if (raw == "null") return null
        return raw.removeSurrounding("\"")
    }

    private fun extractRawValue(json: String, key: String): String {
        val keyPattern = "\"$key\""
        var searchFrom = 0
        while (true) {
            val keyIdx = json.indexOf(keyPattern, searchFrom)
            if (keyIdx < 0) error("Key '$key' not found in JSON fragment")
            // Verify this key is at an object property level (preceded by { , or newline)
            val colonIdx = json.indexOf(':', keyIdx + keyPattern.length)
            if (colonIdx < 0) { searchFrom = keyIdx + 1; continue }
            val valStart = colonIdx + 1
            val trimmed = json.substring(valStart).trimStart()
            // Determine value end
            val valueStr = when {
                trimmed.startsWith("\"") -> {
                    val closeQuote = trimmed.indexOf('"', 1)
                    trimmed.substring(0, closeQuote + 1)
                }
                trimmed.startsWith("{") || trimmed.startsWith("[") -> return trimmed // complex object
                else -> {
                    val end = trimmed.indexOfFirst { it == ',' || it == '}' || it == ']' || it == '\n' }
                    if (end < 0) trimmed.trim() else trimmed.substring(0, end).trim()
                }
            }
            return valueStr
        }
    }
}
