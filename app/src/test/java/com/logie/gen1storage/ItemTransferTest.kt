package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1Items
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.MAX_ITEM_COUNT
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.storage.ItemRepository
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ItemTransferTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `badges are not items`() {
        val save = Gen1RecompSave(SaveFixtures.save())

        val bag = save.bag

        assertTrue(bag.none { it.id in Gen1RecompSave.BADGE_IDS })
        assertEquals(listOf("POTION"), bag.map { it.id })
    }

    @Test
    fun `the save's item PC reads back as counts`() {
        val save = Gen1RecompSave(SaveFixtures.save())

        assertEquals(1, save.pcItems.single().count)
        assertEquals("POTION", save.pcItems.single().id)
    }

    @Test
    fun `a stack will not go past the cap, and says how many went in`() {
        val table = LuaValue.Table().apply { this["POTION"] = luaNum(95.0) }

        val moved = Gen1Items.add(table, "POTION", 10)

        assertEquals(4, moved)
        assertEquals(MAX_ITEM_COUNT, Gen1Items.read(table).single().count)
    }

    @Test
    fun `an emptied entry leaves the table rather than sitting at zero`() {
        val table = LuaValue.Table().apply { this["POTION"] = luaNum(2.0) }

        assertEquals(2, Gen1Items.remove(table, "POTION", 5))

        assertTrue(Gen1Items.read(table).isEmpty())
        assertNull(table["POTION"])
    }

    @Test
    fun `the app's item PC survives a restart`() {
        val directory = temporaryFolder.newFolder()
        ItemRepository(directory).add("ULTRA_BALL", 7)

        assertEquals(7, ItemRepository(directory).count("ULTRA_BALL"))
    }

    @Test
    fun `taking a held item leaves the rest of the Pokemon untouched`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val mon = SaveFixtures.pokemon(
            nickname = "SPARKY",
            extraFields = mapOf("item" to com.logie.gen1storage.lua.luaStr("LEFTOVERS")),
        )
        val before = com.logie.gen1storage.pokemon.Gen1Pokemon(mon)
        val stored = repository.deposit(mon, provenance())!!

        val taken = repository.takeHeldItem(stored.uid)

        assertEquals("LEFTOVERS", taken)
        val after = repository.get(stored.uid)!!.pokemon
        assertNull(after.heldItem)
        assertEquals(before.displayName, after.displayName)
        assertEquals(before.level, after.level)
        assertEquals(before.dvs, after.dvs)
        assertEquals(before.moves.map { it.id }, after.moves.map { it.id })
    }

    @Test
    fun `a Pokemon holding nothing has nothing taken`() {
        val repository = StorageRepository(temporaryFolder.newFolder())
        val stored = repository.deposit(SaveFixtures.pokemon(), provenance())!!

        assertNull(repository.takeHeldItem(stored.uid))
    }

    private fun provenance() = Provenance(
        gameVersion = "red",
        saveId = "test::save.lua",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = 2,
        depositedAtEpochMillis = 1_700_000_000_000,
    )
}
