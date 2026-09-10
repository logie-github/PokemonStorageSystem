package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The invariant under test throughout: a transfer moves a Pokémon. Before it
 * there is exactly one authoritative copy and after it there is exactly one —
 * whether it succeeds, is refused, or is interrupted half way.
 */
class TransferEngineTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var server: FakeSyncServer
    private lateinit var storage: StorageRepository
    private lateinit var journal: TransferJournal
    private lateinit var backups: SaveBackups
    private lateinit var saves: SaveRepository
    private lateinit var engine: TransferEngine

    private val redId = "quiet-forest-dawn"
    private val blueId = "bright-river-noon"

    private fun setUp(
        redParty: List<LuaValue.Table> = listOf(
            SaveFixtures.pokemon(species = "PIKACHU", nickname = "SPARKY"),
            SaveFixtures.pokemon(species = "BULBASAUR", level = 12, otId = 777),
        ),
        redBoxes: List<List<LuaValue.Table>>? = null,
        blueParty: List<LuaValue.Table> = listOf(
            SaveFixtures.pokemon(species = "SQUIRTLE", level = 9, ot = "GARY", otId = 555)
        ),
    ) {
        server = FakeSyncServer()
        server.put(
            "red", redId,
            SaveFixtures.encode(
                SaveFixtures.save(
                    version = "red", trainer = "ASH", party = redParty,
                    boxes = redBoxes, playthroughId = redId,
                )
            ),
        )
        server.put(
            "blue", blueId,
            SaveFixtures.encode(
                SaveFixtures.save(
                    version = "blue", trainer = "GARY", trainerId = 54321,
                    party = blueParty, playthroughId = blueId,
                )
            ),
        )
        val directory = temporaryFolder.newFolder("pc-${System.nanoTime()}")
        storage = StorageRepository(directory)
        journal = TransferJournal(directory)
        backups = SaveBackups(File(directory, "backups"))
        saves = SaveRepository(
            SyncApi(transport = server, credentials = { "acct-1" to "tok-1" }),
            backups,
        )
        engine = TransferEngine(saves, storage, journal) { 1_700_000_000_000 }
    }

    private suspend fun load(playthroughId: String): LoadedSave {
        val state = saves.listSaves().getOrNull()!!
        val remote = state.saves.first { it.playthroughId == playthroughId }
        return saves.load(remote).getOrNull()!!
    }

    private fun saveOn(version: String, playthroughId: String): Gen1RecompSave =
        SaveClassifier.classify(server.blobOf(version, playthroughId)!!).save!!

    // ------------------------------------------------------------------

    @Test
    fun `deposit moves a party Pokemon off the account and into storage`() = runTest {
        setUp()
        val loaded = load(redId)
        val target = loaded.save!!.party[0]
        assertEquals("SPARKY", target.nickname)

        val result = engine.deposit(loaded, SaveLocation.Party(1))
        assertTrue(result.toString(), result is TransferResult.Success)

        val after = saveOn("red", redId)
        assertEquals(1, after.party.size)
        assertEquals("BULBASAUR", after.party[0].speciesId)
        assertEquals(2L, server.revOf("red", redId))

        val stored = storage.state()
        assertEquals(1, stored.total)
        assertEquals(target.fingerprint, stored.boxes[0].contents[0].pokemon.fingerprint)
    }

    @Test
    fun `deposit and withdraw round-trips a Pokemon with no change at all`() = runTest {
        setUp()
        val original = load(redId).save!!.party[0]
        val originalEncoding = LuaWriter.encodeValue(original.raw)

        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        val withdraw = engine.withdraw(load(redId), uid, WithdrawTarget.Party)
        assertTrue(withdraw.toString(), withdraw is TransferResult.Success)

        val returned = saveOn("red", redId).party.first { it.nickname == "SPARKY" }
        assertEquals(originalEncoding, LuaWriter.encodeValue(returned.raw))
        assertEquals(original.fingerprint, returned.fingerprint)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `save A to storage to save B preserves every field`() = runTest {
        setUp()
        val original = load(redId).save!!.party[0]

        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        val withdraw = engine.withdraw(load(blueId), uid, WithdrawTarget.Party)
        assertTrue(withdraw.toString(), withdraw is TransferResult.Success)

        val moved = saveOn("blue", blueId).party.first { it.nickname == "SPARKY" }
        assertEquals(original.fingerprint, moved.fingerprint)
        assertEquals(original.otName, moved.otName)
        assertEquals(original.otId, moved.otId)
        assertEquals(original.exp, moved.exp)
        assertEquals(original.dvs, moved.dvs)
        assertEquals(original.statExp, moved.statExp)
        assertEquals(original.moves, moved.moves)

        assertFalse(saveOn("red", redId).party.any { it.nickname == "SPARKY" })
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `unknown fields on a Pokemon survive the whole journey`() = runTest {
        val exotic = SaveFixtures.pokemon(
            species = "MEW",
            nickname = "MODDED",
            extraFields = mapOf(
                "typeBytes" to LuaValue.Table.ofArray(
                    listOf(com.logie.gen1storage.lua.luaNum(24), com.logie.gen1storage.lua.luaNum(24))
                ),
                "someModField" to com.logie.gen1storage.lua.luaStr("keep me"),
            ),
        )
        setUp(redParty = listOf(exotic, SaveFixtures.pokemon(species = "BULBASAUR")))

        val before = load(redId).save!!.party[0]
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        engine.withdraw(load(blueId), uid, WithdrawTarget.Box(3))

        val moved = saveOn("blue", blueId).boxes[2].single()
        assertEquals(before.fingerprint, moved.fingerprint)
        assertEquals("keep me", (moved.raw["someModField"] as LuaValue.Str).value)
        assertNotNull(moved.raw["typeBytes"])
    }

    @Test
    fun `withdrawing a box Pokemon with no stat block derives stats for the party`() = runTest {
        val statless = SaveFixtures.pokemon(species = "MACHOKE", level = 40, withStats = false, hp = 60)
        setUp(
            redParty = listOf(SaveFixtures.pokemon(), SaveFixtures.pokemon(species = "BULBASAUR")),
            redBoxes = listOf(listOf(statless)),
        )
        val uid = (engine.deposit(load(redId), SaveLocation.Box(1, 1)) as TransferResult.Success).storedUid!!

        // Stored untouched: no stat block was invented while it sat in the PC.
        assertNull(storage.get(uid)!!.pokemon.maxHp)

        engine.withdraw(load(blueId), uid, WithdrawTarget.Party)
        val moved = saveOn("blue", blueId).party.first { it.speciesId == "MACHOKE" }
        assertNotNull(moved.maxHp)
        assertEquals(9, moved.dvs[Gen1Stat.HP])
        assertEquals(
            com.logie.gen1storage.pokemon.Gen1Stats.calcOne(80, 9, 0, 40, true),
            moved.stats[Gen1Stat.HP],
        )
        assertEquals(60, moved.currentHp)
    }

    @Test
    fun `withdrawing into a box does not invent a stat block`() = runTest {
        val statless = SaveFixtures.pokemon(species = "MACHOKE", level = 40, withStats = false, hp = 60)
        setUp(
            redParty = listOf(SaveFixtures.pokemon(), SaveFixtures.pokemon(species = "BULBASAUR")),
            redBoxes = listOf(listOf(statless)),
        )
        val uid = (engine.deposit(load(redId), SaveLocation.Box(1, 1)) as TransferResult.Success).storedUid!!
        engine.withdraw(load(blueId), uid, WithdrawTarget.Box(1))
        assertNull(saveOn("blue", blueId).boxes[0].single().maxHp)
    }

    @Test
    fun `the last party Pokemon cannot be deposited`() = runTest {
        setUp(redParty = listOf(SaveFixtures.pokemon(species = "PIKACHU")))
        val result = engine.deposit(load(redId), SaveLocation.Party(1))
        assertTrue(result is TransferResult.Refused)
        assertEquals(1, saveOn("red", redId).party.size)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `a full party refuses a withdrawal and nothing is lost`() = runTest {
        setUp(blueParty = (1..6).map { SaveFixtures.pokemon(species = "RATTATA", level = it) })
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!

        val result = engine.withdraw(load(blueId), uid, WithdrawTarget.Party)
        assertTrue(result is TransferResult.Refused)
        assertEquals(6, saveOn("blue", blueId).party.size)
        assertEquals(1, storage.state().total)
    }

    @Test
    fun `a full box refuses a withdrawal`() = runTest {
        setUp(redBoxes = listOf(SaveFixtures.fullBox()))
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        val result = engine.withdraw(load(redId), uid, WithdrawTarget.Box(1))
        assertTrue(result is TransferResult.Refused)
        assertEquals(20, saveOn("red", redId).boxes[0].size)
        assertEquals(1, storage.state().total)
    }

    @Test
    fun `a save the game changed since it was opened is refused, not overwritten`() = runTest {
        setUp()
        val stale = load(redId)
        // The game plays on and syncs while the app holds an older read.
        server.gameSaves(
            "red", redId,
            SaveFixtures.encode(
                SaveFixtures.save(
                    trainer = "ASH", playthroughId = redId, playTime = 99999.0,
                    party = listOf(
                        SaveFixtures.pokemon(nickname = "SPARKY"),
                        SaveFixtures.pokemon(species = "CHARMANDER"),
                    ),
                )
            ),
        )
        val result = engine.deposit(stale, SaveLocation.Party(1))
        assertTrue(result is TransferResult.Refused)
        assertTrue((result as TransferResult.Refused).reason.contains("CHANGED"))
        assertEquals(99999.0, saveOn("red", redId).playTimeSeconds, 0.001)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `a race between the read and the upload is caught by the server`() = runTest {
        setUp()
        val loaded = load(redId)
        // Deliberately reach past the engine's own re-read to prove the
        // server's baseRev check is a real second line of defence.
        val mutated = Gen1RecompSave(loaded.save!!.root.deepCopy())
        mutated.removeFromParty(1)
        server.gameSaves("red", redId, SaveFixtures.encode(SaveFixtures.save(playthroughId = redId)))

        val outcome = saves.commit(loaded, mutated.root)
        assertTrue(outcome is com.logie.gen1storage.sync.CommitOutcome.Conflict)
    }

    @Test
    fun `the blob being replaced is backed up before any write goes out`() = runTest {
        setUp()
        val before = server.blobOf("red", redId)
        engine.deposit(load(redId), SaveLocation.Party(1))

        val stored = backups.list("red/$redId")
        assertEquals(1, stored.size)
        assertEquals(before, backups.read(stored.single()))
    }

    @Test
    fun `an interrupted deposit rolls back because the save never changed`() = runTest {
        setUp()
        val original = server.blobOf("red", redId)
        server.failNext = "PUT /sync/save"

        val result = engine.deposit(load(redId), SaveLocation.Party(1))
        assertTrue(result.toString(), result is TransferResult.NeedsRecovery)
        // Both copies exist right now, and the journal says which is which.
        assertEquals(1, storage.state().total)
        assertEquals(original, server.blobOf("red", redId))
        assertNotNull(journal.read())

        val report = engine.recover(saves.listSaves().getOrNull()!!.saves)
        assertTrue(report.unresolved.isEmpty())
        assertTrue(report.resolved.single().contains("rolled back"))
        assertEquals(0, storage.state().total)
        assertEquals(2, saveOn("red", redId).party.size)
        assertNull(journal.read())
    }

    @Test
    fun `a deposit whose reply was lost rolls forward, because the write landed`() = runTest {
        setUp()
        // The server applies the write and then the connection dies: the app
        // cannot know the outcome, which is exactly what recovery settles.
        server.loseReplyFor = "PUT /sync/save"

        val result = engine.deposit(load(redId), SaveLocation.Party(1))
        assertTrue(result.toString(), result is TransferResult.NeedsRecovery)
        assertEquals(1, storage.state().total)
        assertEquals(1, saveOn("red", redId).party.size)

        val report = engine.recover(saves.listSaves().getOrNull()!!.saves)
        assertTrue(report.unresolved.isEmpty())
        assertTrue(report.resolved.single().contains("completed"))
        // Still exactly one copy: the PC keeps it, the save no longer has it.
        assertEquals(1, storage.state().total)
        assertEquals(1, saveOn("red", redId).party.size)
        assertNull(journal.read())
    }

    @Test
    fun `a withdrawal whose reply was lost rolls forward and clears the PC copy`() = runTest {
        setUp()
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        server.loseReplyFor = "PUT /sync/save"

        val result = engine.withdraw(load(blueId), uid, WithdrawTarget.Party)
        assertTrue(result is TransferResult.NeedsRecovery)
        // The save has it and so does the PC, until recovery decides.
        assertEquals(1, storage.state().total)
        assertTrue(saveOn("blue", blueId).party.any { it.nickname == "SPARKY" })

        val report = engine.recover(saves.listSaves().getOrNull()!!.saves)
        assertTrue(report.resolved.single().contains("completed"))
        assertEquals(0, storage.state().total)
        assertTrue(saveOn("blue", blueId).party.any { it.nickname == "SPARKY" })
    }

    @Test
    fun `an interrupted transfer the game then overwrote is never guessed at`() = runTest {
        setUp()
        server.failNext = "PUT /sync/save"
        engine.deposit(load(redId), SaveLocation.Party(1))

        server.gameSaves("red", redId, SaveFixtures.encode(SaveFixtures.save(playthroughId = redId, playTime = 4242.0)))

        val report = engine.recover(saves.listSaves().getOrNull()!!.saves)
        assertTrue(report.resolved.isEmpty())
        assertEquals(1, report.unresolved.size)
        // Nothing deleted on either side, and the record is still there.
        assertEquals(1, storage.state().total)
        assertNotNull(journal.read())
    }

    @Test
    fun `a conflict on upload leaves no duplicate in storage`() = runTest {
        setUp()
        val loaded = load(redId)
        // A second app instance writes between this app's re-read and its PUT.
        val racer = TransferEngine(saves, StorageRepository(temporaryFolder.newFolder()), TransferJournal(temporaryFolder.newFolder()))
        racer.deposit(load(redId), SaveLocation.Party(1))

        val result = engine.deposit(loaded, SaveLocation.Party(1))
        assertTrue(result is TransferResult.Refused)
        assertEquals(0, storage.state().total)
        assertNull(journal.read())
    }

    @Test
    fun `a second transfer is refused while one is unresolved`() = runTest {
        setUp()
        server.failNext = "PUT /sync/save"
        engine.deposit(load(redId), SaveLocation.Party(1))

        val second = engine.deposit(load(redId), SaveLocation.Party(1))
        assertTrue(second is TransferResult.Refused)
        assertTrue((second as TransferResult.Refused).reason.contains("UNRESOLVED"))
    }

    @Test
    fun `a revoked token refuses the write without touching storage`() = runTest {
        setUp()
        val loaded = load(redId)
        server.revokeToken = true
        val result = engine.deposit(loaded, SaveLocation.Party(1))
        assertTrue(result is TransferResult.Refused)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `storage keeps provenance across a restart`() = runTest {
        setUp()
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        storage.reload()
        val stored = storage.get(uid)!!
        assertEquals("red", stored.provenance.gameVersion)
        assertEquals("ASH", stored.provenance.trainerName)
        assertEquals(12345, stored.provenance.trainerId)
        assertEquals(redId, stored.provenance.playthroughId)
        assertEquals("SPARKY", stored.pokemon.nickname)
    }

    @Test
    fun `provenance is never written into the save`() = runTest {
        setUp()
        val uid = (engine.deposit(load(redId), SaveLocation.Party(1)) as TransferResult.Success).storedUid!!
        engine.withdraw(load(blueId), uid, WithdrawTarget.Party)
        val blob = server.blobOf("blue", blueId)!!
        assertFalse(blob.contains("provenance"))
        assertFalse(blob.contains("depositedAt"))
        assertFalse(blob.contains(uid))
    }

    @Test
    fun `a whole box round-trips through storage without change`() = runTest {
        setUp(redBoxes = listOf(SaveFixtures.fullBox()))
        val originals = load(redId).save!!.boxes[0].map { it.fingerprint }

        val uids = mutableListOf<String>()
        repeat(20) {
            val result = engine.deposit(load(redId), SaveLocation.Box(1, 1))
            uids += (result as TransferResult.Success).storedUid!!
        }
        assertEquals(20, storage.state().total)
        assertTrue(saveOn("red", redId).boxes[0].isEmpty())

        uids.forEach { uid ->
            val result = engine.withdraw(load(redId), uid, WithdrawTarget.Box(1))
            assertTrue(result.toString(), result is TransferResult.Success)
        }
        assertEquals(0, storage.state().total)
        assertEquals(originals, saveOn("red", redId).boxes[0].map { it.fingerprint })
    }

    @Test
    fun `fingerprints identify a Pokemon by content, not by table order`() {
        val a = SaveFixtures.pokemon(nickname = "SAME")
        val b = SaveFixtures.pokemon(nickname = "SAME")
        assertEquals(Gen1Pokemon(a).fingerprint, Gen1Pokemon(b).fingerprint)
        assertTrue(
            Gen1Pokemon(a).fingerprint != Gen1Pokemon(SaveFixtures.pokemon(nickname = "SAME", level = 26)).fingerprint
        )
    }
}
