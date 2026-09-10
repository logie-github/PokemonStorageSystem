package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.SaveClassification
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.gen1recomp.SaveDiscovery
import com.logie.gen1storage.gen1recomp.SaveOrigin
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveDiscoveryTest {

    private fun volumeWithSaves(): FakeVolume {
        val volume = FakeVolume()
        val base = "Android/data/com.theboisclub.pokemonred.androidfixes/files/save/pokemon-love2d"
        volume.putFile(
            "$base/saves/red/slot1.lua",
            SaveFixtures.encode(SaveFixtures.save(version = "red", trainer = "ASH")),
        )
        volume.putFile(
            "$base/saves/red/slot2.lua",
            SaveFixtures.encode(SaveFixtures.save(version = "red", trainer = "RED", trainerId = 4242)),
        )
        volume.putFile(
            "$base/saves/blue/slot1.lua",
            SaveFixtures.encode(SaveFixtures.save(version = "blue", trainer = "GARY")),
        )
        volume.putFile(
            "$base/save_yellow.lua",
            SaveFixtures.encode(SaveFixtures.save(version = "yellow", trainer = "SATOSHI")),
        )
        volume.putFile("$base/options.lua", optionsWithSlots())
        // Files that must be ignored.
        volume.putFile("$base/mods/example/init.lua", "return { name = \"example\" }")
        volume.putFile("$base/notes.txt", "hello")
        return volume
    }

    private fun optionsWithSlots(): String {
        val root = LuaValue.Table()
        root["saveSlots"] = LuaValue.Table().apply {
            this["red"] = LuaValue.Table().apply {
                this["list"] = LuaValue.Table.ofArray(listOf(luaStr("slot1"), luaStr("slot2")))
                this["active"] = luaStr("slot2")
                this["names"] = LuaValue.Table().apply { this["slot2"] = luaStr("NUZLOCKE") }
            }
        }
        root["safeMode"] = LuaValue.Bool(false)
        return LuaWriter.encode(root)
    }

    @Test
    fun `finds every slot and flat save, and ignores everything else`() = runTest {
        val volume = volumeWithSaves()
        val result = SaveDiscovery(volume).scan(listOf(volume.root()))

        val paths = result.sources.map { it.relativePath }
        assertEquals(
            listOf("saves/red/slot1.lua", "saves/red/slot2.lua", "saves/blue/slot1.lua", "save_yellow.lua"),
            paths,
        )
        assertTrue(result.sources.all { it.isUsable })
    }

    @Test
    fun `reads the game, trainer and slot identity from the save itself`() = runTest {
        val volume = volumeWithSaves()
        val sources = SaveDiscovery(volume).scan(listOf(volume.root())).sources

        val red1 = sources.first { it.relativePath == "saves/red/slot1.lua" }
        assertEquals(GameVersion.RED, red1.version)
        assertEquals("ASH", red1.trainerName)
        assertEquals(12345, red1.trainerId)
        assertEquals("slot1", red1.slotId)
        assertFalse(red1.isActiveSlot)

        val red2 = sources.first { it.relativePath == "saves/red/slot2.lua" }
        assertTrue(red2.isActiveSlot)
        assertEquals("NUZLOCKE", red2.slotLabel)

        val yellow = sources.first { it.relativePath == "save_yellow.lua" }
        assertEquals(GameVersion.YELLOW, yellow.version)
        assertNull(yellow.slotId)
    }

    @Test
    fun `a corrupt primary falls back to the backup and says so`() = runTest {
        val volume = FakeVolume()
        val base = "files/save/pokemon-love2d"
        val good = SaveFixtures.encode(SaveFixtures.save(trainer = "ASH"))
        volume.putFile("$base/save.lua", "return { this is not valid")
        volume.putFile("$base/save.lua.bak", good)

        val source = SaveDiscovery(volume).scan(listOf(volume.root())).sources.single()
        assertEquals(SaveOrigin.BACKUP, source.origin)
        assertTrue(source.isUsable)
        assertEquals("ASH", source.trainerName)
    }

    @Test
    fun `a staged witness is preferred over a backup, as upstream load does`() = runTest {
        val volume = FakeVolume()
        val base = "files/save/pokemon-love2d"
        volume.putFile("$base/save.lua", "corrupt")
        volume.putFile("$base/save.lua.tmp", SaveFixtures.encode(SaveFixtures.save(trainer = "STAGED")))
        volume.putFile("$base/save.lua.bak", SaveFixtures.encode(SaveFixtures.save(trainer = "OLDER")))

        val source = SaveDiscovery(volume).scan(listOf(volume.root())).sources.single()
        assertEquals(SaveOrigin.STAGED, source.origin)
        assertEquals("STAGED", source.trainerName)
    }

    @Test
    fun `a Gen II save is reported as out of scope, never parsed as Gen I`() {
        val gold = LuaValue.Table().apply {
            this["version"] = luaStr("gold")
            this["generation"] = luaNum(2)
            this["player"] = LuaValue.Table().apply { this["name"] = luaStr("KRIS") }
            this["party"] = LuaValue.Table()
        }
        val classification = SaveClassifier.classify(LuaWriter.encode(gold))
        assertTrue(classification is SaveClassification.WrongGeneration)
        assertNull(classification.save)
    }

    @Test
    fun `options and mod files are not mistaken for saves`() {
        assertTrue(SaveClassifier.classify(optionsWithSlots()) is SaveClassification.Unsupported)
        assertTrue(
            SaveClassifier.classify("return { name = \"a mod\" }") is SaveClassification.Unsupported
        )
    }

    @Test
    fun `an untagged save is Red, as upstream migration 2 decides`() {
        val root = SaveFixtures.save()
        root.remove(com.logie.gen1storage.lua.LuaKey.Name("version"))
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertEquals(GameVersion.RED, classification.save?.version)
    }

    @Test
    fun `malformed source is classified, not thrown`() {
        val classification = SaveClassifier.classify("return { party = ")
        assertTrue(classification is SaveClassification.Malformed)
        assertNotNull((classification as SaveClassification.Malformed).offset)
    }

    @Test
    fun `a save missing its party is incomplete rather than unsupported`() {
        val root = SaveFixtures.save()
        root.remove(com.logie.gen1storage.lua.LuaKey.Name("party"))
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.Incomplete)
        assertEquals(listOf("party"), (classification as SaveClassification.Incomplete).missing)
    }

    @Test
    fun `an over-full party is flagged but still readable`() {
        val root = SaveFixtures.save(party = (1..8).map { SaveFixtures.pokemon(level = it) })
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.ValidWithInvalidPokemon)
        assertNotNull(classification.save)
    }

    @Test
    fun `a modded species is carried, not rejected`() {
        val root = SaveFixtures.save(
            party = listOf(SaveFixtures.pokemon(species = "TOTALLY_CUSTOM_MON", withStats = false))
        )
        val classification = SaveClassifier.classify(LuaWriter.encode(root))
        assertTrue(classification is SaveClassification.Valid)
        assertTrue((classification as SaveClassification.Valid).warnings.any { it.contains("NON-VANILLA") })
    }

    @Test
    fun `the fingerprint changes when and only when the bytes do`() = runTest {
        val volume = volumeWithSaves()
        val first = SaveDiscovery(volume).scan(listOf(volume.root())).sources
        val second = SaveDiscovery(volume).scan(listOf(volume.root())).sources
        assertEquals(first.map { it.fingerprint }, second.map { it.fingerprint })

        volume.putFile(
            "Android/data/com.theboisclub.pokemonred.androidfixes/files/save/pokemon-love2d/saves/red/slot1.lua",
            SaveFixtures.encode(SaveFixtures.save(trainer = "ASH", playTime = 9999.0)),
        )
        val third = SaveDiscovery(volume).scan(listOf(volume.root())).sources
        assertTrue(first.first().fingerprint != third.first().fingerprint)
    }
}
