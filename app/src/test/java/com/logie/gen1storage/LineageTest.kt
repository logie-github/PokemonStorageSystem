package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.storage.Hop
import com.logie.gen1storage.storage.Lineage
import com.logie.gen1storage.storage.LineageBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The mark the app puts on a Pokémon, and the history it keeps beside it. */
class LineageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun book() = LineageBook(temporaryFolder.newFolder())

    @Test
    fun `stamping and unstamping leave the table as it was`() {
        val mon = SaveFixtures.pokemon(species = "PIKACHU", nickname = "SPARKY")
        val before = LuaValue.Table().also { copy ->
            mon.entries().forEach { (key, value) -> copy[key] = value }
        }
        Lineage.stamp(mon, "abc123")
        assertEquals("abc123", Lineage.tagOf(mon))
        Lineage.unstamp(mon)
        assertNull(Lineage.tagOf(mon))
        assertEquals(before.entries().size, mon.entries().size)
    }

    @Test
    fun `a Pokemon that levels up keeps its derived identity`() {
        val young = SaveFixtures.pokemon(species = "PIKACHU", nickname = "SPARKY", level = 5)
        val grown = SaveFixtures.pokemon(species = "PIKACHU", nickname = "BOLT", level = 42)
        // The fixtures agree about the trainer and the DVs, which is all the
        // derived identity is made of — so a season of play does not change it
        // and the app can still tell it is the same creature.
        assertEquals(
            Lineage.identityOf(Gen1Pokemon(young)),
            Lineage.identityOf(Gen1Pokemon(grown)),
        )
    }

    @Test
    fun `a different trainer is a different Pokemon`() {
        val mine = SaveFixtures.pokemon(species = "PIKACHU", ot = "RED", otId = 1234)
        val theirs = SaveFixtures.pokemon(species = "PIKACHU", ot = "BLUE", otId = 9999)
        assertNotEquals(
            Lineage.identityOf(Gen1Pokemon(mine)),
            Lineage.identityOf(Gen1Pokemon(theirs)),
        )
    }

    @Test
    fun `the book hands a history back by tag and by identity`() {
        val book = book()
        val mon = Gen1Pokemon(SaveFixtures.pokemon(species = "PIKACHU"))
        val lineage = Lineage("tag-1").then(Hop(Hop.Kind.DEPOSITED, 1, "red", "red-1", "RED"))
        book.record(lineage, Lineage.identityOf(mon))

        assertEquals(lineage, book.of("tag-1"))
        assertEquals(lineage, book.byIdentity(Lineage.identityOf(mon)!!))
        assertNull(book.of("tag-2"))
    }

    @Test
    fun `a history survives being written and read back`() {
        val directory = temporaryFolder.newFolder()
        val lineage = Lineage("tag-1")
            .then(Hop(Hop.Kind.DEPOSITED, 1, "red", "red-1", "RED"))
            .then(Hop(Hop.Kind.WITHDRAWN, 2, "crystal", "crystal-1", "KRIS"))
            .then(Hop(Hop.Kind.DEPOSITED, 3, "crystal", "crystal-1", "KRIS"))
        LineageBook(directory).record(lineage, "identity-1")

        val reopened = LineageBook(directory).of("tag-1")!!
        assertEquals(3, reopened.hops.size)
        assertEquals(listOf("red", "crystal", "crystal"), reopened.hops.map { it.gameVersion })
        assertEquals(Hop.Kind.WITHDRAWN, reopened.lastOut?.kind)
        // It came back from Crystal, so the app is not holding it out there.
        assertTrue(!reopened.isOut)
    }

    @Test
    fun `a history is capped rather than growing for ever`() {
        var lineage = Lineage("tag-1")
        repeat(Lineage.MAX_HOPS + 20) { at ->
            lineage = lineage.then(Hop(Hop.Kind.DEPOSITED, at.toLong(), "red"))
        }
        assertEquals(Lineage.MAX_HOPS, lineage.hops.size)
        // The oldest go first: what is kept is the most recent run of stops.
        assertEquals(20L, lineage.hops.first().atMillis)
    }
}
