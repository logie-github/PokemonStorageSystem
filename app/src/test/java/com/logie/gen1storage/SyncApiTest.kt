package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.sync.getOrNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire protocol, exercised end to end against a server that enforces the
 * same rules the real one does. Field names and endpoints come from upstream
 * `src/sync/SyncClient.lua`.
 */
class SyncApiTest {

    private val server = FakeSyncServer()
    private var credentials: Pair<String, String>? = "acct-1" to "tok-1"
    private val api = SyncApi(transport = server, credentials = { credentials })

    @Test
    fun `codes are eight digits, punctuation ignored`() {
        assertEquals("49043294", SyncApi.normalizeCode("4904-3294"))
        assertEquals("49043294", SyncApi.normalizeCode(" 4904 3294 "))
        assertNull(SyncApi.normalizeCode("4904-329"))
        assertNull(SyncApi.normalizeCode("abcd-efgh"))
        assertEquals("4904-3294", SyncApi.formatCode("49043294"))
    }

    @Test
    fun `linking returns the account and device token`() = runTest {
        credentials = null
        val result = api.link("4904-3294", "0955-0471", "test device")
        val link = result.getOrNull()!!
        assertEquals("acct-1", link.account)
        assertEquals("tok-1", link.deviceToken)
        assertEquals("POST /sync/link", server.calls.single())
    }

    @Test
    fun `wrong codes are refused without a token`() = runTest {
        credentials = null
        assertTrue(api.link("1111-1111", "2222-2222", "x") is SyncResult.Unauthorized)
    }

    @Test
    fun `a short code never reaches the network`() = runTest {
        credentials = null
        assertTrue(api.link("123", "0955-0471", "x") is SyncResult.Failed)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `state lists Generation I saves and sets aside anything else`() = runTest {
        server.put("red", "quiet-forest-dawn", SaveFixtures.encode(SaveFixtures.save()))
        server.put("blue", "bright-river-noon", SaveFixtures.encode(SaveFixtures.save(version = "blue")))
        server.put("gold", "gen-two-save", "return {}")

        val state = api.state().getOrNull()!!
        assertEquals(2, state.saves.size)
        assertEquals(listOf("gold/gen-two-save"), state.unsupported)
        assertEquals(GameVersion.RED, state.saves[0].version)
        assertEquals("quiet-forest-dawn", state.saves[0].playthroughId)
        assertEquals(1, state.devices.size)
        assertTrue(state.devices.single().isThisDevice)
    }

    @Test
    fun `a save round-trips through the API byte for byte`() = runTest {
        val blob = SaveFixtures.encode(SaveFixtures.save(trainer = "ASH"))
        server.put("red", "quiet-forest-dawn", blob)

        val fetched = api.getSave(GameVersion.RED, "quiet-forest-dawn").getOrNull()!!
        assertEquals(blob, fetched.blob)
        assertEquals(1L, fetched.rev)
    }

    @Test
    fun `a write against a stale revision is refused, not forced`() = runTest {
        val blob = SaveFixtures.encode(SaveFixtures.save())
        server.put("red", "quiet-forest-dawn", blob)
        // The game saves again; the app is still holding revision 1.
        server.gameSaves("red", "quiet-forest-dawn", SaveFixtures.encode(SaveFixtures.save(playTime = 999.0)))

        val result = api.putSave(GameVersion.RED, "quiet-forest-dawn", "slot1", blob, baseRev = 1L, summary = null)
        assertTrue(result is SyncResult.Conflict)
        assertEquals(2L, (result as SyncResult.Conflict).remoteRev)
        // And the server still holds the game's version.
        assertTrue(server.blobOf("red", "quiet-forest-dawn")!!.contains("999"))
    }

    @Test
    fun `a write at the current revision is accepted and bumps it`() = runTest {
        server.put("red", "quiet-forest-dawn", SaveFixtures.encode(SaveFixtures.save()))
        val updated = SaveFixtures.encode(SaveFixtures.save(trainer = "RED"))
        val rev = api.putSave(GameVersion.RED, "quiet-forest-dawn", "slot1", updated, 1L, null).getOrNull()
        assertEquals(2L, rev)
        assertEquals(updated, server.blobOf("red", "quiet-forest-dawn"))
    }

    @Test
    fun `a revoked token reports unauthorized rather than failing vaguely`() = runTest {
        server.put("red", "quiet-forest-dawn", SaveFixtures.encode(SaveFixtures.save()))
        server.revokeToken = true
        assertTrue(api.state() is SyncResult.Unauthorized)
    }

    @Test
    fun `no credentials means no request is attempted`() = runTest {
        credentials = null
        assertTrue(api.state() is SyncResult.Unauthorized)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `a dropped connection is a failure, never a silent success`() = runTest {
        server.put("red", "quiet-forest-dawn", SaveFixtures.encode(SaveFixtures.save()))
        server.failNext = "GET /sync/state"
        val result = api.state()
        assertTrue(result is SyncResult.Failed)
        assertTrue((result as SyncResult.Failed).message.contains("dropped"))
    }

    @Test
    fun `an oversized save is refused before it reaches the network`() = runTest {
        val huge = "return {" + "a".repeat(SyncApi.MAX_BLOB_BYTES + 1) + "}"
        val result = api.putSave(GameVersion.RED, "x", null, huge, null, null)
        assertTrue(result is SyncResult.Failed)
        assertTrue(server.calls.isEmpty())
    }
}
