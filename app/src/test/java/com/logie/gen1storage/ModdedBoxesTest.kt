package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.getOrNull
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A save from a playthrough running TM32 Double Team, which takes Bill's PC
 * from twelve boxes to twenty-four.
 *
 * Shaped the way that mod leaves a save: its `Boxes.ensure` wrapper fills
 * boxes one to twenty-four and clamps `currentBox` to that range, and it
 * deliberately leaves the twenty-per-box capacity alone. So the only thing
 * that changes about the file is how many box arrays it carries — which is
 * exactly the thing this app used to take on faith.
 *
 * The invariant is the app's own: a transfer moves a Pokémon, and a save that
 * was valid before one is valid after it. A box past the twelfth must be no
 * different from a box before it, and nothing the mod added may be dropped on
 * the way back out.
 */
class ModdedBoxesTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val moddedBoxCount = 24
    private val playthroughId = "quiet-forest-dawn"

    private lateinit var server: FakeSyncServer
    private lateinit var storage: StorageRepository
    private lateinit var engine: TransferEngine

    /** Boxes one to twenty-four, with someone kept in the last one. */
    private fun moddedBoxes(): List<List<LuaValue.Table>> = (1..moddedBoxCount).map { index ->
        when (index) {
            12 -> listOf(SaveFixtures.pokemon(species = "ODDISH", nickname = "TWELVE"))
            moddedBoxCount -> listOf(SaveFixtures.pokemon(species = "ABRA", nickname = "LAST"))
            else -> emptyList()
        }
    }

    private fun setUp(currentBox: Int = 24) {
        server = FakeSyncServer()
        server.put(
            "red", playthroughId,
            SaveFixtures.encode(
                SaveFixtures.save(
                    version = "red",
                    trainer = "ASH",
                    party = listOf(
                        SaveFixtures.pokemon(species = "PIKACHU", nickname = "SPARKY"),
                        SaveFixtures.pokemon(species = "BULBASAUR", level = 12),
                    ),
                    boxes = moddedBoxes(),
                    currentBox = currentBox,
                    playthroughId = playthroughId,
                )
            ),
        )
        val directory = temporaryFolder.newFolder("pc-${System.nanoTime()}")
        storage = StorageRepository(directory)
        engine = TransferEngine(
            SaveRepository(
                SyncApi(transport = server, credentials = { "acct-1" to "tok-1" }),
                SaveBackups(File(directory, "backups")),
            ),
            storage,
            TransferJournal(directory),
        ) { 1_700_000_000_000 }
    }

    private suspend fun load(): LoadedSave {
        val saves = SaveRepository(
            SyncApi(transport = server, credentials = { "acct-1" to "tok-1" }),
            SaveBackups(File(temporaryFolder.root, "unused")),
        )
        val remote = saves.listSaves().getOrNull()!!.saves.first()
        return saves.load(remote).getOrNull()!!
    }

    private fun onServer(): Gen1RecompSave =
        SaveClassifier.classify(server.blobOf("red", playthroughId)!!).save!!

    @Test
    fun `all twenty-four boxes are read, and the ones the mod added are not empty by assumption`() =
        runTest {
            setUp()

            val save = load().save!!

            assertEquals(moddedBoxCount, save.boxCount)
            assertEquals(moddedBoxCount, save.boxes.size)
            assertEquals("LAST", save.boxes[moddedBoxCount - 1].single().nickname)
            assertEquals(2, save.storedCount)
        }

    @Test
    fun `a Pokemon in box twenty-four can be brought into the PC`() = runTest {
        setUp()
        val loaded = load()
        val target = loaded.save!!.boxes[moddedBoxCount - 1].single()

        val result = engine.deposit(loaded, SaveLocation.Box(moddedBoxCount, 1))

        assertTrue(result.toString(), result is TransferResult.Success)
        val after = onServer()
        assertEquals("box twenty-four is empty now", 0, after.boxes[moddedBoxCount - 1].size)
        // Nothing else moved, and no box was lost on the way back out.
        assertEquals(moddedBoxCount, after.boxCount)
        assertEquals("TWELVE", after.boxes[11].single().nickname)
        assertEquals(1, storage.state().total)
        assertEquals(
            target.fingerprint,
            storage.state().boxes[0].contents.single().pokemon.fingerprint,
        )
    }

    @Test
    fun `a Pokemon can be sent back out into box twenty-four`() = runTest {
        setUp()
        val original = load().save!!.party[0]
        val encoding = LuaWriter.encodeValue(original.raw)
        val uid = (engine.deposit(load(), SaveLocation.Party(1)) as TransferResult.Success)
            .storedUid!!

        val result = engine.withdraw(load(), uid, WithdrawTarget.Box(moddedBoxCount))

        assertTrue(result.toString(), result is TransferResult.Success)
        val after = onServer()
        val landed = after.boxes[moddedBoxCount - 1].first { it.nickname == "SPARKY" }
        // The same bytes it left with: a box past the twelfth is an ordinary box.
        assertEquals(encoding, LuaWriter.encodeValue(landed.raw))
        assertEquals(2, after.boxes[moddedBoxCount - 1].size)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `the open box is not moved by a transfer`() = runTest {
        setUp(currentBox = 20)

        engine.deposit(load(), SaveLocation.Party(1))

        // ensureBoxes writes currentBox back before any box is written, so
        // this is where clamping to twelve would have shown.
        assertEquals(20, onServer().currentBox)
        assertEquals(moddedBoxCount, onServer().boxCount)
    }

    @Test
    fun `a modded save is not reported as broken`() = runTest {
        setUp()

        val loaded = load()

        assertTrue(loaded.isUsable)
        assertTrue(
            loaded.classification.summary,
            loaded.classification is com.logie.gen1storage.gen1recomp.SaveClassification.Valid,
        )
    }
}
