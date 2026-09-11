package com.logie.gen1storage

import com.logie.gen1storage.sound.SoundEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sounds the interface asks for.
 *
 * Worth pinning because the recordings are looked up by file name: a typo here
 * is not a crash, it is a sound that is silently never found.
 */
class SoundEffectTest {

    @Test
    fun `every effect names a distinct recording`() {
        val names = SoundEffect.entries.map { it.resourceName }
        assertEquals(names.size, names.toSet().size)
        // Raw resource names: lowercase, digits and underscores only, or the
        // build will not accept the file.
        names.forEach { assertTrue(it, it.matches(Regex("[a-z0-9_]+"))) }
    }

    @Test
    fun `every effect has a distinct id, which is what settings are keyed by`() {
        val ids = SoundEffect.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the recordings are the ones the games call them`() {
        assertEquals("sfx_turn_on_pc", SoundEffect.OPEN_PC.resourceName)
        assertEquals("sfx_press_ab", SoundEffect.CURSOR.resourceName)
        assertEquals("sfx_save", SoundEffect.SAVE.resourceName)
        assertEquals("sfx_start_menu", SoundEffect.OPTIONS.resourceName)
        assertEquals("sfx_turn_off_pc", SoundEffect.LOG_OFF.resourceName)
        assertEquals("sfx_enter_pc", SoundEffect.SELECT.resourceName)
        assertEquals("sfx_withdraw_deposit", SoundEffect.TRANSFER.resourceName)
    }

    @Test
    fun `the menu lists every effect`() {
        assertEquals(
            listOf("OPEN PC", "CURSOR", "SAVE", "OPTIONS", "LOG OFF", "SELECT", "TRANSFER"),
            SoundEffect.entries.map { it.label },
        )
    }
}
