package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.gen1recomp.SaveDiscovery
import com.logie.gen1storage.gen1recomp.SaveSource
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stat
import com.logie.gen1storage.storage.StorageRepository
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

/**
 * The invariant under test throughout: a transfer moves a Pokémon. Before it
 * there is exactly one authoritative copy and after it there is exactly one —
 * whether it succeeds, is refused, or is interrupted half way.
 */
class TransferEngineTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var volume: FakeVolume
    private lateinit var storage: StorageRepository
    private lateinit var journal: TransferJournal
    private lateinit var engine: TransferEngine

    private val base = "files/save/pokemon-love2d"

    private fun setUp(
        redParty: List<com.logie.gen1storage.lua.LuaValue.Table> = listOf(
            SaveFixtures.pokemon(species = "PIKACHU", nickname = "SPARKY"),
            SaveFixtures.pokemon(species = "BULBASAUR", level = 12, otId = 777),
        ),
        redBoxes: List<List<com.logie.gen1storage.lua.LuaValue.Table>>? = null,
        blueParty: List<com.logie.gen1storage.lua.LuaValue.Table> = listOf(
            SaveFixtures.pokemon(species = "SQUIRTLE", level = 9, ot = "GARY", otId = 555)
        ),
    ) {
        volume = FakeVolume()
        volume.putFile(
            "$base/saves/red/slot1.lua",
            SaveFixtures.encode(
                SaveFixtures.save(version = "red", trainer = "ASH", party = redParty, boxes = redBoxes)
            ),
        )
        volume.putFile(
            "$base/saves/blue/slot1.lua",
            SaveFixtures.encode(
                SaveFixtures.save(version = "blue", trainer = "GARY", trainerId = 54321, party = blueParty)
            ),
        )
        val directory = temporaryFolder.newFolder("pc-${System.nanoTime()}")
        storage = StorageRepository(directory)
        journal = TransferJournal(directory)
        engine = TransferEngine(volume, storage, journal) { 1_700_000_000_000 }
    }

    private suspend fun sources(): List<SaveSource> =
        SaveDiscovery(volume).scan(listOf(volume.root())).sources

    private suspend fun red(): SaveSource = sources().first { it.relativePath.contains("red") }
    private suspend fun blue(): SaveSource = sources().first { it.relativePath.contains("blue") }

    private fun saveAt(path: String): Gen1RecompSave =
        SaveClassifier.classify(volume.readFile(path)!!).save!!

    // ------------------------------------------------------------------

    @Test
    fun `deposit moves a party Pokemon out of the save and into storage`() = runTest {
        setUp()
        val source = red()
        val before = saveAt("$base/saves/red/slot1.lua")
        val target = before.party[0]
        assertEquals("SPARKY", target.nickname)

        val result = engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        assertTrue(result.toString(), result is TransferResult.Success)

        val after = saveAt("$base/saves/red/slot1.lua")
        assertEquals(1, after.party.size)
        assertEquals("BULBASAUR", after.party[0].speciesId)

        val stored = storage.state()
        assertEquals(1, stored.total)
        assertEquals("SPARKY", stored.boxes[0].contents[0].pokemon.nickname)
        // Exactly one instance: gone from the save, present once in the PC.
        assertEquals(target.fingerprint, stored.boxes[0].contents[0].pokemon.fingerprint)
    }

    @Test
    fun `deposit and withdraw round-trips a Pokemon with no change at all`() = runTest {
        setUp()
        val original = saveAt("$base/saves/red/slot1.lua").party[0]
        val originalEncoding = LuaWriter.encodeValue(original.raw)

        val deposit = engine.deposit(red(), SaveLocation.Party(1), red().fingerprint)
        val uid = (deposit as TransferResult.Success).storedUid!!

        val withdraw = engine.withdraw(red(), uid, WithdrawTarget.Party, red().fingerprint)
        assertTrue(withdraw.toString(), withdraw is TransferResult.Success)

        val after = saveAt("$base/saves/red/slot1.lua")
        val returned = after.party.first { it.nickname == "SPARKY" }
        assertEquals(originalEncoding, LuaWriter.encodeValue(returned.raw))
        assertEquals(original.fingerprint, returned.fingerprint)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `save A to storage to save B preserves every field`() = runTest {
        setUp()
        val original = saveAt("$base/saves/red/slot1.lua").party[0]

        val deposit = engine.deposit(red(), SaveLocation.Party(1), red().fingerprint)
        val uid = (deposit as TransferResult.Success).storedUid!!

        val withdraw = engine.withdraw(blue(), uid, WithdrawTarget.Party, blue().fingerprint)
        assertTrue(withdraw.toString(), withdraw is TransferResult.Success)

        val blueSave = saveAt("$base/saves/blue/slot1.lua")
        val moved = blueSave.party.first { it.nickname == "SPARKY" }
        assertEquals(original.fingerprint, moved.fingerprint)
        assertEquals(original.otName, moved.otName)
        assertEquals(original.otId, moved.otId)
        assertEquals(original.exp, moved.exp)
        assertEquals(original.dvs, moved.dvs)
        assertEquals(original.statExp, moved.statExp)
        assertEquals(original.moves, moved.moves)

        // And it is gone from Red, and from storage.
        assertFalse(saveAt("$base/saves/red/slot1.lua").party.any { it.nickname == "SPARKY" })
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `unknown fields on a Pokemon survive the whole journey`() = runTest {
        val exotic = SaveFixtures.pokemon(
            species = "MEW",
            nickname = "MODDED",
            extraFields = mapOf(
                "typeBytes" to com.logie.gen1storage.lua.LuaValue.Table.ofArray(
                    listOf(com.logie.gen1storage.lua.luaNum(24), com.logie.gen1storage.lua.luaNum(24))
                ),
                "someModField" to com.logie.gen1storage.lua.luaStr("keep me"),
            ),
        )
        setUp(redParty = listOf(exotic, SaveFixtures.pokemon(species = "BULBASAUR")))

        val before = saveAt("$base/saves/red/slot1.lua").party[0]
        val uid = (engine.deposit(red(), SaveLocation.Party(1), red().fingerprint) as TransferResult.Success)
            .storedUid!!
        engine.withdraw(blue(), uid, WithdrawTarget.Box(3), blue().fingerprint)

        val moved = saveAt("$base/saves/blue/slot1.lua").boxes[2].single()
        assertEquals(before.fingerprint, moved.fingerprint)
        assertEquals("keep me", (moved.raw["someModField"] as com.logie.gen1storage.lua.LuaValue.Str).value)
        assertNotNull(moved.raw["typeBytes"])
    }

    @Test
    fun `withdrawing a box Pokemon with no stat block derives stats only for the party`() = runTest {
        val statless = SaveFixtures.pokemon(species = "MACHOKE", level = 40, withStats = false, hp = 60)
        setUp(
            redParty = listOf(SaveFixtures.pokemon(), SaveFixtures.pokemon(species = "BULBASAUR")),
            redBoxes = listOf(listOf(statless)),
        )
        val uid = (engine.deposit(red(), SaveLocation.Box(1, 1), red().fingerprint) as TransferResult.Success)
            .storedUid!!

        // Stored untouched: no stat block was invented while it sat in the PC.
        assertNull(storage.get(uid)!!.pokemon.maxHp)

        engine.withdraw(blue(), uid, WithdrawTarget.Party, blue().fingerprint)
        val moved = saveAt("$base/saves/blue/slot1.lua").party.first { it.speciesId == "MACHOKE" }
        // Stats.ensure ran, exactly as upstream BoxMenu.withdraw does.
        assertNotNull(moved.maxHp)
        assertEquals(5, moved.stats.size)
        assertTrue(moved.currentHp <= moved.maxHp!!)
        assertEquals(60, moved.currentHp)
        // CalcStat for MACHOKE at level 40: base HP 80, derived HP DV 9
        // (the low bits of 15/12/10/9), no stat experience.
        assertEquals(9, moved.dvs[Gen1Stat.HP])
        assertEquals(
            com.logie.gen1storage.pokemon.Gen1Stats.calcOne(80, 9, 0, 40, true),
            moved.stats[Gen1Stat.HP],
        )
    }

    @Test
    fun `withdrawing into a box does not invent a stat block`() = runTest {
        val statless = SaveFixtures.pokemon(species = "MACHOKE", level = 40, withStats = false, hp = 60)
        setUp(
            redParty = listOf(SaveFixtures.pokemon(), SaveFixtures.pokemon(species = "BULBASAUR")),
            redBoxes = listOf(listOf(statless)),
        )
        val uid = (engine.deposit(red(), SaveLocation.Box(1, 1), red().fingerprint) as TransferResult.Success)
            .storedUid!!
        engine.withdraw(blue(), uid, WithdrawTarget.Box(1), blue().fingerprint)

        val moved = saveAt("$base/saves/blue/slot1.lua").boxes[0].single()
        assertNull(moved.maxHp)
    }

    @Test
    fun `the last party Pokemon cannot be deposited`() = runTest {
        setUp(redParty = listOf(SaveFixtures.pokemon(species = "PIKACHU")))
        val source = red()
        val result = engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        assertTrue(result is TransferResult.Refused)
        assertEquals(1, saveAt("$base/saves/red/slot1.lua").party.size)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `a full party refuses a withdrawal and nothing is lost`() = runTest {
        setUp(blueParty = (1..6).map { SaveFixtures.pokemon(species = "RATTATA", level = it) })
        val uid = (engine.deposit(red(), SaveLocation.Party(1), red().fingerprint) as TransferResult.Success)
            .storedUid!!

        val result = engine.withdraw(blue(), uid, WithdrawTarget.Party, blue().fingerprint)
        assertTrue(result is TransferResult.Refused)
        assertEquals(6, saveAt("$base/saves/blue/slot1.lua").party.size)
        assertEquals(1, storage.state().total)
        assertNotNull(storage.get(uid))
    }

    @Test
    fun `a full box refuses a withdrawal`() = runTest {
        setUp(redBoxes = listOf(SaveFixtures.fullBox()))
        val uid = (engine.deposit(red(), SaveLocation.Party(1), red().fingerprint) as TransferResult.Success)
            .storedUid!!
        val result = engine.withdraw(red(), uid, WithdrawTarget.Box(1), red().fingerprint)
        assertTrue(result is TransferResult.Refused)
        assertEquals(20, saveAt("$base/saves/red/slot1.lua").boxes[0].size)
        assertEquals(1, storage.state().total)
    }

    @Test
    fun `a save the game changed since the scan is refused, not overwritten`() = runTest {
        setUp()
        val stale = red()
        // The game plays on and saves while the app is holding a stale read.
        volume.putFile(
            "$base/saves/red/slot1.lua",
            SaveFixtures.encode(
                SaveFixtures.save(
                    version = "red",
                    trainer = "ASH",
                    party = listOf(SaveFixtures.pokemon(nickname = "SPARKY"), SaveFixtures.pokemon(species = "CHARMANDER")),
                    playTime = 99999.0,
                )
            ),
        )
        val result = engine.deposit(stale, SaveLocation.Party(1), stale.fingerprint)
        assertTrue(result is TransferResult.Refused)
        assertTrue((result as TransferResult.Refused).reason.contains("CHANGED"))
        // The newer progress is intact.
        assertEquals(99999.0, saveAt("$base/saves/red/slot1.lua").playTimeSeconds, 0.001)
        assertEquals(0, storage.state().total)
    }

    @Test
    fun `every write is staged and backed up before the save is replaced`() = runTest {
        setUp()
        val source = red()
        volume.writes.clear()
        engine.deposit(source, SaveLocation.Party(1), source.fingerprint)

        val relevant = volume.writes.filter { it.contains("saves/red/slot1") }
        assertEquals(
            listOf("$base/saves/red/slot1.lua.tmp", "$base/saves/red/slot1.lua.bak", "$base/saves/red/slot1.lua"),
            relevant,
        )
        // The backup holds the pre-transfer save, and the witness is cleared.
        assertTrue(volume.readFile("$base/saves/red/slot1.lua.bak")!!.contains("SPARKY"))
        assertFalse(volume.exists("$base/saves/red/slot1.lua.tmp"))
    }

    @Test
    fun `an interrupted deposit rolls back because the save never changed`() = runTest {
        setUp()
        val source = red()
        val originalBytes = volume.readFile("$base/saves/red/slot1.lua")!!
        volume.failWriteTo = "$base/saves/red/slot1.lua"

        val result = engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        assertTrue(result is TransferResult.NeedsRecovery)
        // Both copies exist right now, and the journal says which is which.
        assertEquals(1, storage.state().total)
        assertEquals(originalBytes, volume.readFile("$base/saves/red/slot1.lua"))
        assertNotNull(journal.read())

        volume.failWriteTo = null
        val report = engine.recover(sources())
        assertTrue(report.unresolved.isEmpty())
        assertTrue(report.resolved.single().contains("rolled back"))
        // Exactly one copy again: the save's, since the write never landed.
        assertEquals(0, storage.state().total)
        assertEquals(2, saveAt("$base/saves/red/slot1.lua").party.size)
        assertNull(journal.read())
        // The stale witness was cleared so it cannot resurrect the deposit.
        assertFalse(volume.exists("$base/saves/red/slot1.lua.tmp"))
    }

    @Test
    fun `an interrupted deposit whose write did land rolls forward`() = runTest {
        setUp()
        val source = red()
        volume.failWriteTo = "$base/saves/red/slot1.lua"
        engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        val entry = journal.read()!!

        // Simulate the write having reached the volume after all — the process
        // died between the write and the read-back.
        volume.failWriteTo = null
        val mutated = Gen1RecompSave(saveAt("$base/saves/red/slot1.lua").root.deepCopy())
        mutated.removeFromParty(1)
        volume.putFile("$base/saves/red/slot1.lua", LuaWriter.encode(mutated.root))
        assertEquals(entry.saveHashAfter, SaveDiscovery.sha256(volume.readFile("$base/saves/red/slot1.lua")!!.toByteArray(Charsets.ISO_8859_1)))

        val report = engine.recover(sources())
        assertTrue(report.unresolved.isEmpty())
        // The PC keeps it: the save no longer has it.
        assertEquals(1, storage.state().total)
        assertEquals(1, saveAt("$base/saves/red/slot1.lua").party.size)
    }

    @Test
    fun `an interrupted transfer the game then overwrote is never guessed at`() = runTest {
        setUp()
        val source = red()
        volume.failWriteTo = "$base/saves/red/slot1.lua"
        engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        volume.failWriteTo = null

        // The game wrote the save in between: neither hash matches.
        volume.putFile(
            "$base/saves/red/slot1.lua",
            SaveFixtures.encode(SaveFixtures.save(trainer = "ASH", playTime = 424242.0)),
        )
        val report = engine.recover(sources())
        assertTrue(report.resolved.isEmpty())
        assertEquals(1, report.unresolved.size)
        // Nothing was deleted on either side, and the record is still there.
        assertEquals(1, storage.state().total)
        assertNotNull(journal.read())
    }

    @Test
    fun `a refused write leaves no duplicate in storage`() = runTest {
        setUp()
        val source = red()
        volume.failWriteTo = "$base/saves/red/slot1.lua.tmp"
        val result = engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        assertTrue(result is TransferResult.Refused)
        assertEquals(0, storage.state().total)
        assertNull(journal.read())
        assertEquals(2, saveAt("$base/saves/red/slot1.lua").party.size)
    }

    @Test
    fun `a second transfer is refused while one is unresolved`() = runTest {
        setUp()
        val source = red()
        volume.failWriteTo = "$base/saves/red/slot1.lua"
        engine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        volume.failWriteTo = null

        val second = engine.deposit(red(), SaveLocation.Party(1), red().fingerprint)
        assertTrue(second is TransferResult.Refused)
        assertTrue((second as TransferResult.Refused).reason.contains("UNRESOLVED"))
    }

    @Test
    fun `a read-only volume refuses every transfer`() = runTest {
        setUp()
        val readOnly = FakeVolume(canWrite = false)
        readOnly.putFile("$base/saves/red/slot1.lua", volume.readFile("$base/saves/red/slot1.lua")!!)
        val directory = temporaryFolder.newFolder("ro-${System.nanoTime()}")
        val readOnlyEngine = TransferEngine(readOnly, StorageRepository(directory), TransferJournal(directory))
        val source = SaveDiscovery(readOnly).scan(listOf(readOnly.root())).sources.single()

        val result = readOnlyEngine.deposit(source, SaveLocation.Party(1), source.fingerprint)
        assertTrue(result is TransferResult.Refused)
    }

    @Test
    fun `depositing from a legacy single-box save migrates the PC as upstream does`() = runTest {
        volume = FakeVolume()
        volume.putFile(
            "$base/save.lua",
            SaveFixtures.encode(
                SaveFixtures.save(
                    party = listOf(SaveFixtures.pokemon(), SaveFixtures.pokemon(species = "BULBASAUR")),
                    legacyBox = listOf(SaveFixtures.pokemon(species = "ODDISH", level = 14)),
                )
            ),
        )
        val directory = temporaryFolder.newFolder("legacy-${System.nanoTime()}")
        storage = StorageRepository(directory)
        journal = TransferJournal(directory)
        engine = TransferEngine(volume, storage, journal)

        val source = SaveDiscovery(volume).scan(listOf(volume.root())).sources.single()
        assertEquals("ODDISH", source.save!!.boxes[0].single().speciesId)

        val result = engine.deposit(source, SaveLocation.Box(1, 1), source.fingerprint)
        assertTrue(result.toString(), result is TransferResult.Success)

        val after = saveAt("$base/save.lua")
        assertEquals(12, after.boxes.size)
        assertTrue(after.boxes.all { it.isEmpty() })
        assertNull(after.root["box"])
        assertEquals(1, storage.state().total)
    }

    @Test
    fun `storage survives a reload and keeps provenance`() = runTest {
        setUp()
        val uid = (engine.deposit(red(), SaveLocation.Party(1), red().fingerprint) as TransferResult.Success)
            .storedUid!!
        storage.reload()
        val stored = storage.get(uid)!!
        assertEquals("red", stored.provenance.gameVersion)
        assertEquals("ASH", stored.provenance.trainerName)
        assertEquals(12345, stored.provenance.trainerId)
        assertEquals("party", stored.provenance.sourceKind)
        assertEquals(1, stored.provenance.sourceIndex)
        assertEquals("SPARKY", stored.pokemon.nickname)
    }

    @Test
    fun `provenance is never written into the save`() = runTest {
        setUp()
        val uid = (engine.deposit(red(), SaveLocation.Party(1), red().fingerprint) as TransferResult.Success)
            .storedUid!!
        engine.withdraw(blue(), uid, WithdrawTarget.Party, blue().fingerprint)
        val text = volume.readFile("$base/saves/blue/slot1.lua")!!
        assertFalse(text.contains("provenance"))
        assertFalse(text.contains("depositedAt"))
        assertFalse(text.contains(uid))
    }

    @Test
    fun `the whole PC round-trips through storage without change`() = runTest {
        setUp(redBoxes = listOf(SaveFixtures.fullBox()))
        val originals = saveAt("$base/saves/red/slot1.lua").boxes[0].map { it.fingerprint }

        val uids = mutableListOf<String>()
        repeat(20) {
            val source = red()
            val result = engine.deposit(source, SaveLocation.Box(1, 1), source.fingerprint)
            uids += (result as TransferResult.Success).storedUid!!
        }
        assertEquals(20, storage.state().total)
        assertTrue(saveAt("$base/saves/red/slot1.lua").boxes[0].isEmpty())

        uids.forEach { uid ->
            val source = red()
            val result = engine.withdraw(source, uid, WithdrawTarget.Box(1), source.fingerprint)
            assertTrue(result.toString(), result is TransferResult.Success)
        }
        assertEquals(0, storage.state().total)
        assertEquals(originals, saveAt("$base/saves/red/slot1.lua").boxes[0].map { it.fingerprint })
    }

    @Test
    fun `fingerprints identify a Pokemon by content, not by table order`() {
        val a = SaveFixtures.pokemon(nickname = "SAME")
        val b = SaveFixtures.pokemon(nickname = "SAME")
        assertEquals(Gen1Pokemon(a).fingerprint, Gen1Pokemon(b).fingerprint)

        val c = SaveFixtures.pokemon(nickname = "SAME", level = 26)
        assertTrue(Gen1Pokemon(a).fingerprint != Gen1Pokemon(c).fingerprint)
    }
}
