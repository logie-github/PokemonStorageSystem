package com.logie.gen1storage

import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import com.logie.gen1storage.transfer.Mailbox
import com.logie.gen1storage.transfer.SaveLocation
import com.logie.gen1storage.transfer.TransferNote
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Moving a Pokémon by leaving a note for the game.
 *
 * The point of every test here is the same one: at no moment does a Pokémon
 * exist twice for real, or nowhere at all. While a note is outstanding one
 * side owns the Pokémon and the other is showing a picture of it, and which
 * is which is the thing being pinned.
 */
class MailboxTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val server = FakeSyncServer()
    private lateinit var storage: StorageRepository
    private lateinit var api: SyncApi
    private lateinit var mailbox: Mailbox

    private val blob = SaveFixtures.encode(SaveFixtures.save())

    @Before
    fun setUp() {
        server.put("red", "quiet-forest-dawn", blob)
        storage = StorageRepository(temporaryFolder.newFolder("pc-${System.nanoTime()}"))
        api = SyncApi(transport = server, credentials = { "acct-1" to "tok-1" })
        mailbox = Mailbox(api, storage) { 1_700_000_000_000 }
    }

    private suspend fun remote() =
        (api.state() as SyncResult.Ok).value.saves.single { it.version.id == "red" }

    private fun save() = SaveClassifier.classify(blob).save!!

    private fun provenance() = com.logie.gen1storage.storage.Provenance(
        gameVersion = "red",
        saveId = "red/quiet-forest-dawn",
        savePath = "save.lua",
        slotId = "slot1",
        trainerName = "ASH",
        trainerId = 12345,
        playthroughId = "quiet-forest-dawn",
        sourceKind = com.logie.gen1storage.storage.Provenance.KIND_PARTY,
        sourceIndex = 2,
        depositedAtEpochMillis = 1_700_000_000_000,
    )

    // ------- a Pokémon going out to a cartridge

    @Test
    fun `giving one leaves it in the PC, marked, until the game says otherwise`() = runTest {
        val stored = storage.deposit(SaveFixtures.pokemon(), provenance())!!

        val posted = mailbox.give(stored.uid, remote(), TransferNote.Target.Party)
        assertTrue(posted is Mailbox.Posted.Left)

        // Still ours, and not usable for anything else.
        val held = storage.get(stored.uid)
        assertNotNull(held)
        assertTrue(held!!.inFlight)
        // And the save itself was never written to.
        assertEquals(blob, server.blobOf("red", "quiet-forest-dawn"))
        assertEquals(1, server.notes.size)
        assertEquals("give", server.notes.values.single().getString("kind"))
    }

    @Test
    fun `the copy in the PC goes only once the cartridge has it`() = runTest {
        val stored = storage.deposit(SaveFixtures.pokemon(), provenance())!!
        val posted = mailbox.give(stored.uid, remote(), TransferNote.Target.Party)
            as Mailbox.Posted.Left

        // Nothing has happened yet: the note is still sitting there.
        assertTrue(mailbox.reconcile().isEmpty())
        assertNotNull(storage.get(stored.uid))

        server.gameApplies(posted.note.id)
        val settled = mailbox.reconcile().single()
        assertTrue(settled.applied)
        assertNull(storage.get(stored.uid))
        assertTrue(server.notes.isEmpty())
    }

    @Test
    fun `a refused note hands the Pokemon back`() = runTest {
        val stored = storage.deposit(SaveFixtures.pokemon(), provenance())!!
        val posted = mailbox.give(stored.uid, remote(), TransferNote.Target.Party)
            as Mailbox.Posted.Left

        server.gameRefuses(posted.note.id, "THE PARTY IS FULL")
        val settled = mailbox.reconcile().single()

        assertFalse(settled.applied)
        val held = storage.get(stored.uid)
        assertNotNull(held)
        assertFalse(held!!.inFlight)
    }

    @Test
    fun `one already on its way cannot be sent again`() = runTest {
        val stored = storage.deposit(SaveFixtures.pokemon(), provenance())!!
        mailbox.give(stored.uid, remote(), TransferNote.Target.Party)

        val second = mailbox.give(stored.uid, remote(), TransferNote.Target.Box(2))
        assertTrue(second is Mailbox.Posted.Refused)
        assertEquals(1, server.notes.size)
    }

    // ------- a Pokémon coming in from a cartridge

    @Test
    fun `taking one shows it in the box without claiming it`() = runTest {
        val posted = mailbox.take(remote(), save(), SaveLocation.Party(1), targetBox = 1)
        assertTrue(posted is Mailbox.Posted.Left)

        val shown = storage.get((posted as Mailbox.Posted.Left).uid)
        assertNotNull(shown)
        // Visible, and not the player's yet.
        assertTrue(shown!!.inFlight)
        // The cartridge still has the only real one.
        assertEquals(blob, server.blobOf("red", "quiet-forest-dawn"))

        val note = server.notes.values.single()
        assertEquals("take", note.getString("kind"))
        assertEquals(1, note.getJSONObject("from").getInt("party"))
        assertEquals(
            save().party.first().speciesId,
            note.getJSONObject("expect").getString("species"),
        )
    }

    @Test
    fun `it becomes the player's when the cartridge lets go`() = runTest {
        val posted = mailbox.take(remote(), save(), SaveLocation.Party(1), 1)
            as Mailbox.Posted.Left

        server.gameApplies(posted.note.id)
        val settled = mailbox.reconcile().single()

        assertTrue(settled.applied)
        val held = storage.get(posted.uid)
        assertNotNull(held)
        assertFalse(held!!.inFlight)
    }

    @Test
    fun `a refused take takes the placeholder back out`() = runTest {
        val posted = mailbox.take(remote(), save(), SaveLocation.Party(1), 1)
            as Mailbox.Posted.Left

        server.gameRefuses(posted.note.id, "THAT SPOT HAS SOMEONE ELSE IN IT")
        mailbox.reconcile()

        assertNull(storage.get(posted.uid))
        assertTrue(storage.all().isEmpty())
    }

    // ------- the note survives this app

    @Test
    fun `reconciling twice does nothing the second time`() = runTest {
        val stored = storage.deposit(SaveFixtures.pokemon(), provenance())!!
        val posted = mailbox.give(stored.uid, remote(), TransferNote.Target.Party)
            as Mailbox.Posted.Left
        server.gameApplies(posted.note.id)

        assertEquals(1, mailbox.reconcile().size)
        assertEquals(0, mailbox.reconcile().size)
        assertNull(storage.get(stored.uid))
    }

    @Test
    fun `a mark survives the app being restarted`() = runTest {
        val directory = temporaryFolder.newFolder("pc-restart-${System.nanoTime()}")
        val first = StorageRepository(directory)
        val stored = first.deposit(SaveFixtures.pokemon(), provenance())!!
        Mailbox(api, first) { 1_700_000_000_000 }
            .give(stored.uid, remote(), TransferNote.Target.Party)

        // Opened again from the same folder, as a fresh launch would.
        val second = StorageRepository(directory)
        assertTrue(second.get(stored.uid)!!.inFlight)
        assertEquals(1, second.all().count { it.inFlight })
    }
}
