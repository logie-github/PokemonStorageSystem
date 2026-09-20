package com.logie.gen1storage

import com.logie.gen1storage.mods.ModManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `mod.json` is allowed to say, and how little of it there is to say:
 * a name, an author, and a palette that is nothing but five colours — no
 * field here can name a behaviour, only a look.
 */
class ModManifestTest {

    private val fullPalette = """
        {
          "name": "Twilight",
          "author": "Someone",
          "palette": {
            "id": "twilight",
            "label": "TWILIGHT",
            "lightest": "#F0F0FF",
            "light": "#9090CC",
            "dark": "#404070",
            "darkest": "#100818",
            "surround": "#201030",
            "tintsSprites": false
          }
        }
    """.trimIndent()

    @Test
    fun `a full manifest parses every field`() {
        val manifest = ModManifest.parse(fullPalette, fallbackId = "fallback", fallbackName = "Fallback")
        assertEquals("Twilight", manifest.name)
        assertEquals("Someone", manifest.author)
        val palette = manifest.palette!!
        assertEquals("twilight", palette.id)
        assertEquals("TWILIGHT", palette.label)
        assertEquals(0xFFF0F0FF.toInt(), palette.lightest)
        assertEquals(0xFF9090CC.toInt(), palette.light)
        assertEquals(0xFF404070.toInt(), palette.dark)
        assertEquals(0xFF100818.toInt(), palette.darkest)
        assertEquals(0xFF201030.toInt(), palette.surround)
        assertEquals(false, palette.tintsSprites)
    }

    @Test
    fun `a name-only manifest has no palette`() {
        val manifest = ModManifest.parse("""{"name": "Just A Name"}""", fallbackId = "id", fallbackName = "Fallback")
        assertEquals("Just A Name", manifest.name)
        assertNull(manifest.author)
        assertNull(manifest.palette)
    }

    @Test
    fun `an empty manifest falls back to the id and name it was given`() {
        val manifest = ModManifest.parse("{}", fallbackId = "mod-123", fallbackName = "mod-123")
        assertEquals("mod-123", manifest.name)
        assertNull(manifest.author)
        assertNull(manifest.palette)
    }

    @Test
    fun `a palette missing any one colour is dropped rather than half-read`() {
        val json = """
            {"name": "Broken", "palette": {"lightest": "#FFFFFF", "light": "#CCCCCC", "dark": "#333333"}}
        """.trimIndent()
        val manifest = ModManifest.parse(json, fallbackId = "id", fallbackName = "Broken")
        assertNull(manifest.palette)
    }

    @Test
    fun `a palette with an invalid colour string is dropped`() {
        val json = """
            {"name": "Broken", "palette": {
                "lightest": "not a colour", "light": "#CCCCCC", "dark": "#333333",
                "darkest": "#000000", "surround": "#111111"
            }}
        """.trimIndent()
        assertNull(ModManifest.parse(json, "id", "Broken").palette)
    }

    @Test
    fun `a palette without its own id or label falls back to the mod's own name`() {
        val json = """
            {"name": "My Mod", "palette": {
                "lightest": "#FFFFFF", "light": "#CCCCCC", "dark": "#333333",
                "darkest": "#000000", "surround": "#111111"
            }}
        """.trimIndent()
        val palette = ModManifest.parse(json, fallbackId = "abc123", fallbackName = "My Mod").palette!!
        assertEquals("mod_abc123", palette.id)
        assertEquals("MY MOD", palette.label)
        assertTrue("tintsSprites should default to true", palette.tintsSprites)
    }

    @Test
    fun `a colour works with or without its leading hash`() {
        val json = """
            {"palette": {
                "lightest": "FFFFFF", "light": "#CCCCCC", "dark": "333333",
                "darkest": "#000000", "surround": "111111"
            }}
        """.trimIndent()
        val palette = ModManifest.parse(json, "id", "Name").palette!!
        assertEquals(0xFFFFFFFF.toInt(), palette.lightest)
        assertEquals(0xFF000000.toInt(), palette.darkest)
    }
}
