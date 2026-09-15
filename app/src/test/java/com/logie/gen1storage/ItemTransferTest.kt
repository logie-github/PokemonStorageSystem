package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1Items
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.MAX_ITEM_COUNT
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.storage.ItemRepository
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.getOrNull
import com.logie.gen1storage.transfer.ItemTransferEngine
import com.logie.gen1storage.transfer.TransferResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
        ItemRepository(directory).add("ULTRA_BALL", 7, generation = 1)

        assertEquals(7, ItemRepository(directory).count("ULTRA_BALL", generation = 1))
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

    @Test
    fun `the two generations' items never share a stack`() {
        val directory = temporaryFolder.newFolder()
        val repository = ItemRepository(directory)

        repository.add("POTION", 5, generation = 1)
        repository.add("POTION", 3, generation = 2)

        assertEquals(5, repository.count("POTION", generation = 1))
        assertEquals(3, repository.count("POTION", generation = 2))
        assertEquals(listOf(5), repository.state(1).map { it.count })
        assertEquals(listOf(3), repository.state(2).map { it.count })
        // Read together, each stack still says which vocabulary it is in.
        assertEquals(setOf(1, 2), repository.state().map { it.generation }.toSet())

        repository.remove("POTION", 5, generation = 1)
        assertEquals(0, repository.count("POTION", generation = 1))
        assertEquals(3, repository.count("POTION", generation = 2))
    }

    @Test
    fun `a save's own items are tagged with its generation`() {
        val gen1 = Gen1RecompSave(SaveFixtures.save(version = "red"))
        val gen2 = Gen1RecompSave(SaveFixtures.save(version = "gold"))

        assertEquals(1, gen1.pcItems.single().generation)
        assertEquals(2, gen2.pcItems.single().generation)
    }

    @Test
    fun `items move between the app's PC and a Generation II save`() = runTest {
        val server = FakeSyncServer()
        val playthroughId = "still-cave-midnight"
        server.put(
            "gold", playthroughId,
            SaveFixtures.encode(SaveFixtures.save(version = "gold", playthroughId = playthroughId)),
        )
        val directory = temporaryFolder.newFolder()
        val saves = SaveRepository(
            SyncApi(transport = server, credentials = { "acct-1" to "tok-1" }),
            SaveBackups(File(directory, "backups")),
        )
        val items = ItemRepository(directory)
        val engine = ItemTransferEngine(saves, items)

        val state = saves.listSaves().getOrNull()!!
        val remote = state.saves.first { it.playthroughId == playthroughId }
        val loaded = saves.load(remote).getOrNull()!!

        val deposited = engine.deposit(loaded, "POTION", 1)
        assertTrue(deposited is TransferResult.Success)
        assertEquals(1, items.count("POTION", generation = 2))
        assertEquals(0, items.count("POTION", generation = 1))

        val reloaded = saves.load(remote).getOrNull()!!
        val withdrawn = engine.withdraw(reloaded, "POTION", 1)
        assertTrue(withdrawn is TransferResult.Success)
        assertEquals(0, items.count("POTION", generation = 2))
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
