package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen2Mail
import com.logie.gen1storage.pokemon.tradeEvolves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eggs and mail, the two things Generation II keeps that Generation I never
 * had to think about.
 *
 * The shapes are Gen1Recomp's: `mon.isEgg` on the record
 * (`src/core/gen2/Breeding.lua`) and `save.mail.party` indexed by party slot
 * (`src/core/gen2/Mail.lua`, which ports `engine/pokemon/mail.asm`).
 */
class Gen2EggMailTest {

    private fun egg(species: String = "TOGEPI", cycles: Int = 5): Gen1Pokemon {
        val mon = LuaValue.Table()
        mon["species"] = luaStr(species)
        mon["level"] = luaNum(5)
        mon["isEgg"] = LuaValue.Bool(true)
        mon["eggSteps"] = luaNum(cycles)
        return Gen1Pokemon(mon, generation = 2)
    }

    @Test
    fun `an egg never says what is inside it`() {
        val it = egg("DRATINI")
        assertTrue(it.isEgg)
        assertEquals("EGG", it.displayName)
        // The species is still on the record, because the game has to know
        // what will hatch. Nothing that draws may reach it.
        assertEquals("DRATINI", it.speciesId)
        assertNull(it.species)
        assertNull(it.species?.dexNumber)
    }

    @Test
    fun `an egg is not a trade evolution waiting to happen`() {
        // KADABRA would evolve on a trade. In a shell it is not a KADABRA yet,
        // and only HatchEggs may decide otherwise.
        assertFalse(tradeEvolves(egg("KADABRA")))
        val hatched = LuaValue.Table().apply {
            this["species"] = luaStr("KADABRA")
            this["level"] = luaNum(20)
        }
        assertTrue(tradeEvolves(Gen1Pokemon(hatched, generation = 2)))
    }

    @Test
    fun `how close an egg is, read off its hatch counter`() {
        assertEquals(5, egg(cycles = 5).hatchCycles)
        // The byte the cartridge keeps happiness in, for a save that writes
        // it where the cartridge did.
        val carried = LuaValue.Table().apply {
            this["species"] = luaStr("TOGEPI")
            this["isEgg"] = LuaValue.Bool(true)
            this["happiness"] = luaNum(0x28)
        }
        assertEquals(0x28, Gen1Pokemon(carried, generation = 2).hatchCycles)
    }

    @Test
    fun `mail is the ten items the cartridge lists, not anything spelled MAIL`() {
        assertTrue(Gen2Mail.isMail("FLOWER_MAIL"))
        // Neither of these ends in _MAIL, and a test on the spelling misses
        // both. `ItemIsMail` is a search of the list, so this is too.
        assertTrue(Gen2Mail.isMail("LITEBLUEMAIL"))
        assertTrue(Gen2Mail.isMail("PORTRAITMAIL"))
        assertFalse(Gen2Mail.isMail("BERRY"))
        assertFalse(Gen2Mail.isMail(null))
        assertEquals(10, Gen2Mail.ITEMS.size)
    }

    @Test
    fun `taking a Pokemon out of a Gold party moves the letters up behind it`() {
        val root = SaveFixtures.save(
            version = "gold",
            party = (1..4).map { SaveFixtures.pokemon(species = "PIDGEY", level = it * 5) },
        )
        root["generation"] = luaNum(2)
        root["mail"] = LuaValue.Table().apply {
            this["party"] = LuaValue.Table().apply {
                this[2] = letter("FLOWER_MAIL", "SECOND")
                this[3] = letter("SURF_MAIL", "THIRD")
                this[4] = letter("EON_MAIL", "FOURTH")
            }
            this["box"] = LuaValue.Table()
        }
        val save = Gen1RecompSave(root)
        assertTrue(save.isGen2)

        // Slot 2 leaves. Its letter goes with it and the two behind it move up,
        // which is `RemoveMonFromPartyOrBox`'s "Mail time!" tail. Without the
        // shift, slot 2's new occupant would inherit the third Pokemon's.
        save.removeFromParty(2)

        assertEquals("THIRD", Gen2Mail.letter(root, 2)?.message)
        assertEquals("FOURTH", Gen2Mail.letter(root, 3)?.message)
        assertNull(Gen2Mail.letter(root, 4))
        // The slot that was first never moves.
        assertNull(Gen2Mail.letter(root, 1))
    }

    @Test
    fun `a save with no letters is left without a mail table at all`() {
        val root = SaveFixtures.save(version = "gold")
        root["generation"] = luaNum(2)
        Gen1RecompSave(root).removeFromParty(1)
        // Serializing six empty structs into a save that never had any is
        // this app changing a file for no reason.
        assertNull(root["mail"])
    }

    @Test
    fun `a Pokemon holding mail is one the PC will not take`() {
        val holding = LuaValue.Table().apply {
            this["species"] = luaStr("PIDGEY")
            this["item"] = luaStr("MIRAGE_MAIL")
        }
        assertTrue(Gen1Pokemon(holding, generation = 2).holdsMail)

        val holdingSomethingElse = LuaValue.Table().apply {
            this["species"] = luaStr("PIDGEY")
            this["item"] = luaStr("LEFTOVERS")
        }
        assertFalse(Gen1Pokemon(holdingSomethingElse, generation = 2).holdsMail)
    }

    @Test
    fun `pokerus is read as the two nybbles the stats screen reads`() {
        fun mon(pokerus: Int) = Gen1Pokemon(
            LuaValue.Table().apply {
                this["species"] = luaStr("PIDGEY")
                this["pokerus"] = luaNum(pokerus)
            },
            generation = 2,
        )
        // Strain in the high nybble, days left in the low one.
        assertTrue(mon(0x43).hasPokerus)
        assertEquals(3, mon(0x43).pokerusDaysLeft)
        assertFalse(mon(0x43).curedOfPokerus)
        // Run its course: no days left, but the strain is still recorded, and
        // that is the dot the cartridge marks it with.
        assertFalse(mon(0x40).hasPokerus)
        assertTrue(mon(0x40).curedOfPokerus)
        // Never had it.
        assertFalse(mon(0).hasPokerus)
        assertFalse(mon(0).curedOfPokerus)
    }

    private fun letter(type: String, message: String) = LuaValue.Table().apply {
        this["type"] = luaStr(type)
        this["message"] = luaStr(message)
        this["author"] = luaStr("RED")
        this["authorId"] = luaNum(27671)
    }
}
