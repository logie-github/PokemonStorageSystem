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
 * A Generation II save is written, and only ever by its own Pokémon.
 *
 * The shelf lists it, the card draws it, its boxes and its items can be read
 * and written to, the same as any Generation I save. What is still refused,
 * because the games themselves refuse it: a Generation I Pokémon going
 * straight into one (the Time Capsule is the only way across), a Pokémon
 * going back the other way at all, and a write that would change which
 * generation a save is. Each of those is pinned here directly, because one
 * of them being right is not the same as the app being safe.
 */
class Gen2WriteTest {

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
                this["pcItems"] = LuaValue.Table().apply { this["POTION"] = luaNum(1) }
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
        // Judged by Generation II's rules rather than set aside: a party of
        // six Gold Pokemon is not a broken Red party, and CHIKORITA is not a
        // non-vanilla species.
        assertTrue(classification is SaveClassification.Valid)
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
    fun `a Pokemon only ever goes into a cartridge of its own generation`() = runTest {
        val blob = goldSave()
        server.put("gold", "quiet-forest-dawn", blob)
        // A Red save beside it, for the trip that must not be possible back.
        server.put("red", "quiet-forest-dawn", LuaWriter.encode(SaveFixtures.save()))
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

        assertTrue(loaded.isUsable)
        assertTrue(loaded.isWritable)
        assertTrue(GameVersion.GOLD.isWritable)

        // A Generation I Pokemon may not be written into a Gold save. The
        // games have one way across and it is the Time Capsule; putting a
        // Generation I table straight into Gold would leave it holding a
        // Pokemon Gold cannot describe, which is the corruption this app
        // exists to avoid.
        val fromRed = storage.deposit(
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
            generation = 1,
        )!!
        val refused = engine.withdraw(loaded, fromRed.uid, WithdrawTarget.Party)
        assertTrue(refused is TransferResult.Refused)
        assertTrue(
            (refused as TransferResult.Refused).reason.contains("TIME CAPSULE"),
            )
        assertEquals("and the save is untouched", blob, server.blobOf("gold", "quiet-forest-dawn"))

        // The other way is refused as well, and there is no way back at all.
        val fromGold = storage.deposit(
            SaveFixtures.pokemon(),
            fromRed.provenance.copy(gameVersion = "gold"),
            generation = 2,
        )!!
        val redRemote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "red" }
        val red = (saves.load(redRemote) as SyncResult.Ok).value
        val back = engine.withdraw(red, fromGold.uid, WithdrawTarget.Party)
        assertTrue(back is TransferResult.Refused)
        assertTrue((back as TransferResult.Refused).reason.contains("CANNOT GO BACK"))
    }

    @Test
    fun `taking one out of a Gold save leaves it a Gold save`() = runTest {
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

        // The party holds one, so it cannot be the one that leaves.
        val last = engine.deposit(loaded, com.logie.gen1storage.transfer.SaveLocation.Party(1), 1)
        assertTrue(last is TransferResult.Refused)
        assertEquals(blob, server.blobOf("gold", "quiet-forest-dawn"))
    }

    @Test
    fun `a Gold save keeps fourteen boxes and Red keeps twelve`() {
        val gold = SaveClassifier.classify(goldSave()).save!!
        assertEquals(Gen1RecompSave.BOX_COUNT_GEN2, gold.stockBoxes)
        assertEquals(14, gold.boxes.size)

        val red = SaveClassifier.classify(LuaWriter.encode(SaveFixtures.save())).save!!
        assertEquals(Gen1RecompSave.BOX_COUNT, red.stockBoxes)
        assertEquals(12, red.boxes.size)
    }

    @Test
    fun `a write that would change a save's generation is refused`() = runTest {
        val blob = goldSave()
        server.put("gold", "quiet-forest-dawn", blob)
        val api = SyncApi(transport = server, credentials = { "acct-1" to "tok-1" })
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        // The one place every write in the app goes through, asked directly
        // rather than through an engine that might be bypassed.
        val root = loaded.save!!.root.deepCopy()
        root.remove(com.logie.gen1storage.lua.LuaKey.Name("generation"))
        root["version"] = luaStr("red")
        val outcome = saves.commit(loaded, root)
        assertTrue(outcome is CommitOutcome.Refused)
        assertEquals(blob, server.blobOf("gold", "quiet-forest-dawn"))
    }

    @Test
    fun `items cross into and out of a Generation II save`() = runTest {
        // The end-to-end mechanics — both directions, and that the two
        // generations' stacks stay apart — are pinned in ItemTransferTest;
        // this only confirms a Gold save is no longer refused outright the
        // way it once was.
        val blob = goldSave()
        server.put("gold", "quiet-forest-dawn", blob)
        val api = SyncApi(transport = server, credentials = { "acct-1" to "tok-1" })
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val storageDir = temporaryFolder.newFolder("items-${System.nanoTime()}")
        val engine = com.logie.gen1storage.transfer.ItemTransferEngine(
            saves,
            com.logie.gen1storage.storage.ItemRepository(storageDir),
        )
        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        val outcome = engine.deposit(loaded, "POTION", 1)
        assertTrue(outcome is TransferResult.Success)
    }
}
