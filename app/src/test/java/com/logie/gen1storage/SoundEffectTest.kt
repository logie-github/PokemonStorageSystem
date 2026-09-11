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
        val files = SoundEffect.entries.map { it.file }
        assertEquals(files.size, files.toSet().size)
        files.forEach { assertTrue(it, it.endsWith(".ogg")) }
    }

    @Test
    fun `every effect has a distinct id, which is what settings are keyed by`() {
        val ids = SoundEffect.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `the recordings are the ones the games call them`() {
        assertEquals("turn_on_pc.ogg", SoundEffect.OPEN_PC.file)
        assertEquals("press_ab.ogg", SoundEffect.CURSOR.file)
        assertEquals("save.ogg", SoundEffect.SAVE.file)
        assertEquals("start_menu.ogg", SoundEffect.OPTIONS.file)
        assertEquals("turn_off_pc.ogg", SoundEffect.LOG_OFF.file)
        assertEquals("enter_pc.ogg", SoundEffect.SELECT.file)
    }

    @Test
    fun `the menu lists all six`() {
        assertEquals(6, SoundEffect.entries.size)
        assertEquals(
            listOf("OPEN PC", "CURSOR", "SAVE", "OPTIONS", "LOG OFF", "SELECT"),
            SoundEffect.entries.map { it.label },
        )
    }
}
