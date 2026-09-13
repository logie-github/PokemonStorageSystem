package com.logie.gen1storage

import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.SaveBackups
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What this app tells the server about a save it has written.
 *
 * The bytes are the save's own. The index beside them is not quite: it claims
 * a minute of play the save has not had, which is the one thing that makes
 * Gen1Recomp ask which copy to keep rather than quietly keeping its own. See
 * [SaveRepository.commit].
 */
class SaveRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = FakeSyncServer()

    private fun repository(): SaveRepository {
        val directory = temporaryFolder.newFolder("saves-${System.nanoTime()}")
        return SaveRepository(
            SyncApi(transport = server, credentials = { "acct-1" to "tok-1" }),
            SaveBackups(File(directory, "backups")),
        )
    }

    @Test
    fun `the play time that goes up is a minute past the save's own`() = runTest {
        // 7265.5 seconds is two hours, one minute and five seconds: "2:01".
        val blob = SaveFixtures.encode(SaveFixtures.save())
        server.put("red", "quiet-forest-dawn", blob)
        val saves = repository()
        val account = (saves.listSaves() as SyncResult.Ok).value
        val loaded = (saves.load(account.saves.single()) as SyncResult.Ok).value

        val outcome = saves.commit(loaded, loaded.save!!.root)
        assertTrue(outcome is CommitOutcome.Committed)

        val meta = server.metaOf("red", "quiet-forest-dawn")
        assertNotNull(meta)
        assertEquals("2:02", meta!!.getJSONObject("summary").getString("timeText"))
        assertEquals(7325.5, meta.getDouble("playTime"), 0.001)
    }

    @Test
    fun `the save itself keeps its own clock`() = runTest {
        val blob = SaveFixtures.encode(SaveFixtures.save())
        server.put("red", "quiet-forest-dawn", blob)
        val saves = repository()
        val account = (saves.listSaves() as SyncResult.Ok).value
        val loaded = (saves.load(account.saves.single()) as SyncResult.Ok).value

        val outcome = saves.commit(loaded, loaded.save!!.root) as CommitOutcome.Committed

        // The bytes on the server, and the copy this app goes on holding.
        assertEquals(7265.5, outcome.after.save!!.playTimeSeconds, 0.001)
        assertEquals("2:01", outcome.after.save!!.playTimeText)
        assertEquals("2:01", outcome.after.remote.summary.timeText)
        assertTrue(server.blobOf("red", "quiet-forest-dawn")!!.contains("7265.5"))
    }
}
