package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen2Mail
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.transfer.MailEngine
import com.logie.gen1storage.transfer.TransferResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [MailEngine] against a live (faked) account: the same reload-and-commit
 * discipline every other write path here has, proven the same way
 * [Gen2WriteTest] proves [com.logie.gen1storage.transfer.TransferEngine]'s.
 */
class MailEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = FakeSyncServer()

    private fun goldSaveWithMailHolder(): String = LuaWriter.encode(
        LuaValue.Table().apply {
            this["version"] = luaStr("gold")
            this["generation"] = luaNum(2)
            this["format"] = luaNum(8)
            this["player"] = LuaValue.Table().apply {
                this["name"] = luaStr("KRIS")
                this["id"] = luaNum(4242)
            }
            this["party"] = LuaValue.Table().apply {
                setArray(
                    listOf(
                        SaveFixtures.pokemon(species = "PIDGEY").apply {
                            this["item"] = luaStr("FLOWER_MAIL")
                        },
                        SaveFixtures.pokemon(species = "SPEAROW"),
                    )
                )
            }
            this["mail"] = LuaValue.Table().apply {
                this["party"] = LuaValue.Table().apply {
                    this[1] = LuaValue.Table().apply {
                        this["type"] = luaStr("FLOWER_MAIL")
                        this["message"] = luaStr("Good luck out there!")
                        this["author"] = luaStr("KRIS")
                    }
                }
            }
        }
    )

    private fun engine(): Pair<MailEngine, SyncApi> {
        val api = SyncApi(transport = server, credentials = { "acct-1" to "tok-1" })
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        return MailEngine(SaveRepository(api, backups)) to api
    }

    @Test
    fun `SEND MAIL TO PC commits a save with the letter filed and the item gone`() = runTest {
        val blob = goldSaveWithMailHolder()
        server.put("gold", "quiet-forest-dawn", blob)
        val (mailEngine, api) = engine()
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        val result = mailEngine.sendToPc(loaded, 1)
        assertTrue(result is TransferResult.Success)

        val after = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val written = (SaveRepository(api, backups).load(after) as SyncResult.Ok).value.save!!
        assertEquals(null, written.party[0].heldItem)
        assertEquals(1, Gen2Mail.mailboxCount(written.root))
        assertEquals("Good luck out there!", Gen2Mail.mailbox(written.root).single().message)
    }

    @Test
    fun `SEND MAIL TO PC refuses a Pokemon that isn't holding any, and writes nothing`() = runTest {
        val blob = goldSaveWithMailHolder()
        server.put("gold", "quiet-forest-dawn", blob)
        val (mailEngine, api) = engine()
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        // Slot 2, SPEAROW, holds nothing.
        val result = mailEngine.sendToPc(loaded, 2)
        assertTrue(result is TransferResult.Refused)
        assertEquals(blob, server.blobOf("gold", "quiet-forest-dawn"))
    }

    @Test
    fun `ATTACH MAIL refuses an egg and refuses a Pokemon already holding something`() = runTest {
        val blob = LuaWriter.encode(
            LuaValue.Table().apply {
                this["version"] = luaStr("gold")
                this["generation"] = luaNum(2)
                this["format"] = luaNum(8)
                this["player"] = LuaValue.Table().apply {
                    this["name"] = luaStr("KRIS")
                    this["id"] = luaNum(4242)
                }
                this["party"] = LuaValue.Table().apply {
                    setArray(
                        listOf(
                            SaveFixtures.pokemon(species = "TOGEPI").apply {
                                this["isEgg"] = LuaValue.Bool(true)
                            },
                            SaveFixtures.pokemon(species = "SPEAROW").apply {
                                this["item"] = luaStr("LEFTOVERS")
                            },
                            SaveFixtures.pokemon(species = "PIDGEY"),
                        )
                    )
                }
                this["mail"] = LuaValue.Table().apply {
                    this["box"] = LuaValue.Table().apply {
                        setArray(
                            listOf(
                                LuaValue.Table().apply {
                                    this["type"] = luaStr("MUSIC_MAIL")
                                    this["message"] = luaStr("La la la")
                                    this["author"] = luaStr("KRIS")
                                }
                            )
                        )
                    }
                }
            }
        )
        server.put("gold", "quiet-forest-dawn", blob)
        val (mailEngine, api) = engine()
        val backups = SaveBackups(temporaryFolder.newFolder("backups-${System.nanoTime()}"))
        val saves = SaveRepository(api, backups)
        val remote = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val loaded = (saves.load(remote) as SyncResult.Ok).value

        val onEgg = mailEngine.attachFromMailbox(loaded, 1, 1)
        assertTrue(onEgg is TransferResult.Refused)
        assertTrue((onEgg as TransferResult.Refused).reason.contains("EGG"))

        val onHolder = mailEngine.attachFromMailbox(loaded, 1, 2)
        assertTrue(onHolder is TransferResult.Refused)

        assertEquals("and nothing was written by either refusal", blob, server.blobOf("gold", "quiet-forest-dawn"))

        // The third slot is free and no egg: this is the one that actually works.
        val ok = mailEngine.attachFromMailbox(loaded, 1, 3)
        assertTrue(ok is TransferResult.Success)
        val after = (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "gold" }
        val written = (SaveRepository(api, backups).load(after) as SyncResult.Ok).value.save!!
        assertEquals("MUSIC_MAIL", written.party[2].heldItem)
        assertEquals(0, Gen2Mail.mailboxCount(written.root))
    }
}
