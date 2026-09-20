package com.logie.gen1storage

import com.logie.gen1storage.mods.ModBinaryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModBinaryRulesTest {

    @Test
    fun `a blacklisted extension is rejected before a single byte is read`() {
        val violation = ModBinaryRules.checkHeader("cartridge.gbc", ByteArray(0))
        assertNotNull(violation)
        assertEquals("Blacklisted Extension", violation!!.ruleName)
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertNotNull(ModBinaryRules.checkHeader("CARTRIDGE.GBC", ByteArray(0)))
    }

    @Test
    fun `a plain png passes the extension check`() {
        assertNull(ModBinaryRules.checkHeader("sprite.png", ByteArray(4)))
    }

    @Test
    fun `a Game Boy cartridge header is caught by its Nintendo logo`() {
        // The real 48-byte Nintendo logo pret's own hardware.asm carries at
        // 0x0104 in every real Game Boy cartridge, however this one was
        // named or however small the rest of the file is.
        val logo = byteArrayOf(
            0xce.toByte(), 0xed.toByte(), 0x66, 0x66, 0xcc.toByte(), 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83.toByte(), 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88.toByte(), 0x89.toByte(), 0x00, 0x0e,
            0xac.toByte(), 0xcf.toByte(),
        )
        val header = ByteArray(0x0104 + logo.size)
        logo.copyInto(header, 0x0104)
        val violation = ModBinaryRules.checkHeader("not_a_rom.dat", header)
        assertNotNull(violation)
        assertEquals("Game Boy / GBC Nintendo Logo", violation!!.ruleName)
    }

    @Test
    fun `a header too short to reach a rule's offset is not a match`() {
        assertNull(ModBinaryRules.checkHeader("tiny.bin", ByteArray(4)))
    }

    @Test
    fun `an ordinary sprite header matches nothing`() {
        // A PNG signature plus a few bytes of an IHDR chunk — nothing here
        // should trip any console header rule.
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
        )
        assertNull(ModBinaryRules.checkHeader("sprite.png", png))
    }

    @Test
    fun `a contained signature is found inside a non-text binary`() {
        val bytes = "leading junk before RARC and after".toByteArray()
        val violation = ModBinaryRules.checkHeader("bundle.bin", bytes)
        assertNotNull(violation)
        assertEquals("GameCube / Wii RARC Archive", violation!!.ruleName)
    }

    @Test
    fun `the same bytes inside a lua script are not scanned for contained signatures`() {
        // Source and config files are exempt from the deep scan — the four
        // letters RARC turning up in the middle of somebody's variable name
        // is not a GameCube archive.
        val bytes = "-- a script that mentions RARCHIVE_NAME in a comment".toByteArray()
        assertNull(ModBinaryRules.checkHeader("script.lua", bytes))
    }

    @Test
    fun `extensionOf handles no extension and multiple dots`() {
        assertEquals("", ModBinaryRules.extensionOf("README"))
        assertEquals(".png", ModBinaryRules.extensionOf("front.v2.png"))
    }
}
