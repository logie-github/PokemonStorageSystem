package com.logie.gen1storage

import com.logie.gen1storage.mods.DHash
import com.logie.gen1storage.mods.ModReferenceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModReferenceDatabaseTest {

    @Test
    fun `an empty database matches nothing`() {
        assertNull(ModReferenceDatabase.EMPTY.closestMatch(LongArray(4)))
    }

    @Test
    fun `parse reads every hash out of the json`() {
        val db = ModReferenceDatabase.parse(
            """
            {"hashSize": 16, "hashes": {
                "a": "${"0".repeat(64)}",
                "b": "${"f".repeat(64)}"
            }}
            """.trimIndent()
        )
        assertEquals(2, db.size)
    }

    @Test
    fun `parse skips a hash that isn't valid hex`() {
        val db = ModReferenceDatabase.parse(
            """{"hashSize": 16, "hashes": {"good": "${"0".repeat(64)}", "bad": "not hex"}}"""
        )
        assertEquals(1, db.size)
    }

    @Test
    fun `closestMatch finds the nearest of several, not just the first`() {
        val target = LongArray(4)
        val near = longArrayOf(1L, 0, 0, 0)
        val far = longArrayOf(-1L, -1L, -1L, -1L)
        val db = ModReferenceDatabase.parse(
            """
            {"hashSize": 16, "hashes": {
                "far": "${DHash.toHex(far)}",
                "near": "${DHash.toHex(near)}"
            }}
            """.trimIndent()
        )
        val (key, distance) = db.closestMatch(target)!!
        assertEquals("near", key)
        assertEquals(1, distance)
    }

    @Test
    fun `an exact match is zero bits away`() {
        val hash = longArrayOf(123L, 456L, 0, -1L)
        val db = ModReferenceDatabase.parse("""{"hashSize": 16, "hashes": {"exact": "${DHash.toHex(hash)}"}}""")
        val (key, distance) = db.closestMatch(hash)!!
        assertEquals("exact", key)
        assertEquals(0, distance)
    }
}
