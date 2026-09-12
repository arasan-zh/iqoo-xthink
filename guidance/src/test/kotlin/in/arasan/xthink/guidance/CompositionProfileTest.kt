package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompositionProfileTest {

    @Test
    fun `parses every shot type from the JSON the app actually ships`() {
        val profiles = CompositionProfile.parseAll(Fixtures.JSON)
        assertEquals(ShotType.entries.toSet(), profiles.keys)
    }

    @Test
    fun `reads the headshot row exactly`() {
        val p = CompositionProfile.parse(Fixtures.JSON, ShotType.HEADSHOT)
        assertEquals(ShotType.HEADSHOT, p.shotType)
        assertEquals(0.45f, p.targetSizeRatio, 1e-6f)
        assertEquals(0.33f, p.targetEyeLineY, 1e-6f)
        assertEquals(0.50f, p.targetCx, 1e-6f)
        assertEquals(0.06f, p.headroomMin, 1e-6f)
        assertEquals(0.0f, p.targetPitchDeg, 1e-6f)
    }

    @Test
    fun `landscape has no size target, which is what disables the distance rung`() {
        val p = CompositionProfile.parse(Fixtures.JSON, ShotType.LANDSCAPE)
        assertEquals(0.0f, p.targetSizeRatio, 1e-6f)
    }

    @Test
    fun `tolerates whitespace, exponents and unknown keys`() {
        val json = """
            {
              "HEADSHOT":  { "targetSizeRatio": 4.5e-1, "targetEyeLineY": 0.33,
                             "targetCx": 0.50, "headroomMin": 0.06,
                             "targetPitchDeg": -0.0, "notes": "ignored", "enabled": true },
              "HALF_BODY": { "targetSizeRatio": 0.25, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
              "FULL_BODY": { "targetSizeRatio": 0.10, "targetEyeLineY": 0.28, "targetCx": 0.50, "headroomMin": 0.10, "targetPitchDeg": 0.0 },
              "GROUP":     { "targetSizeRatio": 0.18, "targetEyeLineY": 0.35, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
              "OBJECT":    { "targetSizeRatio": 0.55, "targetEyeLineY": 0.50, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
              "LANDSCAPE": { "targetSizeRatio": 0.00, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.00, "targetPitchDeg": 0.0 }
            }
        """.trimIndent()
        val p = CompositionProfile.parse(json, ShotType.HEADSHOT)
        assertEquals(0.45f, p.targetSizeRatio, 1e-6f)
    }

    @Test
    fun `a missing shot type names itself in the error`() {
        val json = """{ "HEADSHOT": { "targetSizeRatio": 0.45, "targetEyeLineY": 0.33, "targetCx": 0.5, "headroomMin": 0.06, "targetPitchDeg": 0.0 } }"""
        try {
            CompositionProfile.parseAll(json)
            fail("expected a JsonException")
        } catch (e: JsonException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("HALF_BODY"))
        }
    }

    @Test
    fun `a missing field names itself in the error`() {
        val json = Fixtures.JSON.replace("\"targetCx\": 0.50, \"headroomMin\": 0.06", "\"headroomMin\": 0.06")
        try {
            CompositionProfile.parseAll(json)
            fail("expected a JsonException")
        } catch (e: JsonException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("targetCx"))
        }
    }

    @Test(expected = JsonException::class)
    fun `rejects malformed JSON`() {
        CompositionProfile.parseAll("{ \"HEADSHOT\": { ")
    }

    @Test(expected = JsonException::class)
    fun `rejects a non-object document`() {
        CompositionProfile.parseAll("[1, 2, 3]")
    }

    @Test(expected = JsonException::class)
    fun `rejects a non-numeric field`() {
        CompositionProfile.parseAll(Fixtures.JSON.replace("0.45", "\"0.45\""))
    }
}

class MiniJsonTest {

    @Test
    fun `reads the primitive types`() {
        val o = MiniJson.parseObject("""{"a":1,"b":-2.5,"c":"x","d":true,"e":false,"f":null,"g":[1,"two",null]}""")
        assertEquals(1.0, o["a"])
        assertEquals(-2.5, o["b"])
        assertEquals("x", o["c"])
        assertEquals(true, o["d"])
        assertEquals(false, o["e"])
        assertEquals(null, o["f"])
        assertEquals(listOf(1.0, "two", null), o["g"])
    }

    @Test
    fun `reads nested objects and empty containers`() {
        val o = MiniJson.parseObject("""{"outer":{"inner":{"v":7}},"none":{},"empty":[]}""")
        @Suppress("UNCHECKED_CAST")
        val outer = o["outer"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val inner = outer["inner"] as Map<String, Any?>
        assertEquals(7.0, inner["v"])
        assertEquals(emptyMap<String, Any?>(), o["none"])
        assertEquals(emptyList<Any?>(), o["empty"])
    }

    @Test
    fun `reads string escapes`() {
        val raw = """{"s":"q\"b\\c\/d\ne\tf\u0041\b"}"""
        val o = MiniJson.parseObject(raw)
        assertEquals("q\"b\\c/d\ne\tfA\b", o["s"])
    }

    @Test(expected = JsonException::class)
    fun `rejects trailing content`() {
        MiniJson.parse("""{"a":1} junk""")
    }

    @Test(expected = JsonException::class)
    fun `rejects an unterminated string`() {
        MiniJson.parse("""{"a":"oops}""")
    }
}
