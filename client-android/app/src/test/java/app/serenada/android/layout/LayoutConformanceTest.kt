package app.serenada.android.layout

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val STRICT_TOLERANCE = 0.005

@RunWith(Parameterized::class)
class LayoutConformanceTest(
    private val caseId: String,
    private val json: JSONObject,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val stream = LayoutConformanceTest::class.java
                .getResourceAsStream("/fixtures/layout_conformance_v1.json")
                ?: error("Fixture file not found")
            val root = JSONObject(stream.bufferedReader().readText())
            val casesArray = root.getJSONArray("cases")
            return (0 until casesArray.length()).map { i ->
                val case = casesArray.getJSONObject(i)
                arrayOf(case.getString("id"), case)
            }
        }
    }

    @Test
    fun conformance() {
        val scene = parseScene(json.getJSONObject("scene"))
        val expected = json.getJSONObject("expected")
        val result = computeLayout(scene)

        // Mode
        assertEquals(
            "mode mismatch for $caseId",
            expected.getString("mode"),
            result.mode.name.lowercase(),
        )

        // Tile count
        assertEquals(
            "tile count mismatch for $caseId",
            expected.getInt("tileCount"),
            result.tiles.size,
        )

        // Tile frames
        val expectedTiles = expected.getJSONArray("tiles")
        for (i in 0 until expectedTiles.length()) {
            val et = expectedTiles.getJSONObject(i)
            val at = result.tiles[i]

            assertEquals("tile[$i] id for $caseId", et.getString("id"), at.id)
            assertEquals("tile[$i] fit for $caseId", et.getString("fit"), at.fit.name.lowercase())

            val expectedFrame = et.getJSONObject("normalizedFrame")
            val actualFrame = normalizeFrame(at.frame, scene.viewportWidth, scene.viewportHeight)
            assertFrameClose(actualFrame, expectedFrame, STRICT_TOLERANCE, "$caseId tile[$i]")
        }

        // Local PIP
        if (expected.isNull("localPip")) {
            assertNull("localPip should be null for $caseId", result.localPip)
        } else {
            assertNotNull("localPip should not be null for $caseId", result.localPip)
            val expectedPip = expected.getJSONObject("localPip")
            val pip = result.localPip!!

            assertEquals(
                "pip participantId for $caseId",
                expectedPip.getString("participantId"),
                pip.participantId,
            )
            assertEquals(
                "pip anchor for $caseId",
                expectedPip.getString("anchor"),
                anchorToString(pip.anchor),
            )

            val expectedPipFrame = expectedPip.getJSONObject("normalizedFrame")
            val actualPipFrame = normalizeFrame(pip.frame, scene.viewportWidth, scene.viewportHeight)
            assertFrameClose(actualPipFrame, expectedPipFrame, STRICT_TOLERANCE, "$caseId pip")
        }
    }

    private fun parseScene(json: JSONObject): CallScene {
        val participants = json.getJSONArray("participants").let { arr ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                SceneParticipant(
                    id = p.getString("id"),
                    role = if (p.getString("role") == "local") ParticipantRole.LOCAL else ParticipantRole.REMOTE,
                    videoEnabled = p.getBoolean("videoEnabled"),
                    videoAspectRatio = if (p.isNull("videoAspectRatio")) null else p.getDouble("videoAspectRatio").toFloat(),
                )
            }
        }

        val insets = json.getJSONObject("safeAreaInsets")
        val userPrefs = if (json.has("userPrefs")) {
            val up = json.getJSONObject("userPrefs")
            UserLayoutPrefs(
                swappedLocalAndRemote = up.optBoolean("swappedLocalAndRemote", false),
                dominantFit = if (up.optString("dominantFit", "cover") == "contain") FitMode.CONTAIN else FitMode.COVER,
            )
        } else {
            UserLayoutPrefs()
        }

        val contentSource = if (json.isNull("contentSource")) null else {
            val cs = json.getJSONObject("contentSource")
            ContentSource(
                type = when (cs.getString("type")) {
                    "worldCamera" -> ContentType.WORLD_CAMERA
                    "compositeCamera" -> ContentType.COMPOSITE_CAMERA
                    else -> ContentType.SCREEN_SHARE
                },
                ownerParticipantId = cs.getString("ownerParticipantId"),
                aspectRatio = if (cs.isNull("aspectRatio")) null else cs.getDouble("aspectRatio").toFloat(),
            )
        }

        return CallScene(
            viewportWidth = json.getDouble("viewportWidth").toFloat(),
            viewportHeight = json.getDouble("viewportHeight").toFloat(),
            safeAreaInsets = Insets(
                top = insets.getDouble("top").toFloat(),
                bottom = insets.getDouble("bottom").toFloat(),
                left = insets.getDouble("left").toFloat(),
                right = insets.getDouble("right").toFloat(),
            ),
            participants = participants,
            localParticipantId = json.getString("localParticipantId"),
            activeSpeakerId = if (json.isNull("activeSpeakerId")) null else json.getString("activeSpeakerId"),
            pinnedParticipantId = if (json.isNull("pinnedParticipantId")) null else json.getString("pinnedParticipantId"),
            contentSource = contentSource,
            userPrefs = userPrefs,
        )
    }

    private data class NormalizedFrame(val x: Double, val y: Double, val width: Double, val height: Double)

    private fun normalizeFrame(frame: LayoutRect, viewportWidth: Float, viewportHeight: Float): NormalizedFrame {
        return NormalizedFrame(
            x = frame.x.toDouble() / viewportWidth,
            y = frame.y.toDouble() / viewportHeight,
            width = frame.width.toDouble() / viewportWidth,
            height = frame.height.toDouble() / viewportHeight,
        )
    }

    private fun assertFrameClose(actual: NormalizedFrame, expected: JSONObject, tolerance: Double, label: String) {
        val ex = expected.getDouble("x")
        val ey = expected.getDouble("y")
        val ew = expected.getDouble("width")
        val eh = expected.getDouble("height")

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
}
