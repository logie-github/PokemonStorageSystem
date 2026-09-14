package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.transfer.PlacementLedger
import com.logie.gen1storage.transfer.TransferEngine
import com.logie.gen1storage.transfer.TransferJournal
import com.logie.gen1storage.transfer.TransferResult
import com.logie.gen1storage.transfer.WithdrawTarget
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A Generation II save is read and never written.
 *
 * Read: the shelf lists it, the card draws it, its boxes can be looked
 * through. Never written: its Pokémon carry fields Generation I has no place
 * for — a held item, happiness, a Special that has been split in two — and a
 * transfer written by this app's engine would drop them. The refusal is in
 * three places and this pins all three, because one of them being right is
 * not the same as the app being safe.
 */
class Gen2ReadOnlyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = FakeSyncServer()

    private fun goldSave(): String {
        val mon = SaveFixtures.pokemon().apply {
            this["item"] = luaStr("BERRY")
            this["happiness"] = luaNum(70)
        }
        return LuaWriter.encode(
            LuaValue.Table().apply {
                this["version"] = luaStr("gold")
                this["generation"] = luaNum(2)
                this["format"] = luaNum(8)
                this["player"] = LuaValue.Table().apply {
                    this["name"] = luaStr("KRIS")
                    this["id"] = luaNum(4242)
                    this["gender"] = luaStr("female")
                    this["money"] = luaNum(3000)
                    this["badges"] = LuaValue.Table().apply {
                        this["ZEPHYRBADGE"] = LuaValue.Bool(true)
                        this["HIVEBADGE"] = LuaValue.Bool(true)
                    }
                    this["kantoBadges"] = LuaValue.Table().apply {
                        this["BOULDERBADGE"] = LuaValue.Bool(true)
                    }
                }
                this["party"] = LuaValue.Table().apply { setArray(listOf(mon)) }
                this["playTime"] = LuaValue.Table().apply {
                    this["hours"] = luaNum(12)
                    this["minutes"] = luaNum(34)
                    this["seconds"] = luaNum(56)
                }
                this["pokedex"] = LuaValue.Table().apply {
                    this["caught"] = LuaValue.Table().apply {
                        this["CHIKORITA"] = LuaValue.Bool(true)
                    }
                }
            }
        )
    }

    @Test
    fun `a Gold save reads as a trainer card`() {
        val classification = SaveClassifier.classify(goldSave())
        assertTrue(classification is SaveClassification.ReadOnlyGeneration)
        val save = classification.save
        assertNotNull(save)
        save!!

        assertTrue(save.isGen2)
        assertEquals("KRIS", save.trainerName)
        assertEquals(4242, save.trainerId)
        assertTrue(save.isFemale)
        assertEquals(3000, save.money)
        // Hours, minutes and seconds kept apart, as the cartridge kept them.
        assertEquals("12:34", save.playTimeText)
        // Two of Johto's, one of Kanto's, and the two sets are not confused.
        assertEquals(2, save.johtoBadges.count { it })
        assertEquals(1, save.kantoBadges.count { it })
        assertEquals(1, save.partyCount)
        assertEquals(1, save.dexOwnedCount)
    }

    @Test
    fun `the Pokemon's own fields survive being read`() {
        val save = SaveClassifier.classify(goldSave()).save!!
        val lead = save.party.first()
        // Whatever else it is, what a Generation II Pokémon carries is still
        // there to be seen: this app reads the table rather than a Generation
        // I shape of it.
        assertEquals("BERRY", lead.heldItem)
    }

    @Test
    fun `nothing in the app will write one`() = runTest {
        val blob = goldSave()
        server.put("gold", "quiet-forest-dawn", blob)
        val api = SyncApi(transport = server, credentials = { "acct-1" to "tok-1" })
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val storageDir = temporaryFolder.newFolder("pc-${System.nanoTime()}")
        val storage = StorageRepository(storageDir)
        val engine = TransferEngine(
            saves,
            storage,
            TransferJournal(storageDir),
            PlacementLedger(storageDir),
        )

        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        // Readable, and not writable: both halves matter.
        assertTrue(loaded.isUsable)
        assertFalse(loaded.isWritable)
        assertFalse(GameVersion.GOLD.isWritable)

        // Taking one out of it.
        val deposit = engine.deposit(loaded, com.logie.gen1storage.transfer.SaveLocation.Party(1), 1)
        assertTrue(deposit is TransferResult.Refused)

        // Putting one into it.
        val stored = storage.deposit(
            SaveFixtures.pokemon(),
            com.logie.gen1storage.storage.Provenance(
                gameVersion = "red",
                saveId = "red/quiet-forest-dawn",
                savePath = "save.lua",
                slotId = "slot1",
                trainerName = "ASH",
                trainerId = 12345,
                playthroughId = "quiet-forest-dawn",
                sourceKind = com.logie.gen1storage.storage.Provenance.KIND_PARTY,
                sourceIndex = 1,
                depositedAtEpochMillis = 1_700_000_000_000,
            ),
        )!!
        val withdraw = engine.withdraw(loaded, stored.uid, WithdrawTarget.Party)
        assertTrue(withdraw is TransferResult.Refused)

        // And the one place every write in the app goes through, asked
        // directly rather than through an engine that might be bypassed.
        val outcome = saves.commit(loaded, Gen1RecompSave(loaded.save!!.root.deepCopy()).root)
        assertTrue(outcome is CommitOutcome.Refused)

        // The save on the server is untouched, byte for byte.
        assertEquals(blob, server.blobOf("gold", "quiet-forest-dawn"))
    }
}
