package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StoredPokemon
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SyncApi
import com.logie.gen1storage.sync.SyncResult
import java.util.UUID

/**
 * Moving a Pokémon by asking the game to move it.
 *
 * The engine beside this one changes a cartridge by rewriting its save. That
 * works and it forks: the game is editing the same file, and when both have,
 * one of them loses whatever it did. This one never touches a save. It leaves
 * a note — *put this one in*, *take that one out* — and the game applies it to
 * its own file the next time it syncs, which is the moment the player switches
 * back to it. One writer, and nothing to reconcile.
 *
 * ## What a Pokémon is while a note is outstanding
 *
 * Exactly one side owns it at every moment, which is the same promise the
 * journal makes for a direct write, kept over minutes instead of milliseconds.
 *
 * - **Going out** ([give]): the Pokémon stays in this app's PC, marked with
 *   the note. It is still ours — the cartridge has not been given it yet — and
 *   it cannot be sent anywhere else, released, or traded until we hear. When
 *   the note comes back applied, our copy goes, because by then the cartridge
 *   has it.
 * - **Coming in** ([take]): the Pokémon is put in the box straight away,
 *   marked with the note, but it is *not ours yet*: the cartridge still holds
 *   the only real one. It shows where it will be and can do nothing until the
 *   cartridge says it has let go. If the note is refused it disappears again,
 *   because it was never ours.
 *
 * So a player watching either direction sees the transfer happen at once, and
 * the one thing they cannot do is act on that Pokémon again before the game
 * has caught up — which is a greyed sprite, not a wait.
 *
 * ## Recovery
 *
 * [reconcile] is the whole of it, and it runs from the server's list rather
 * than from anything remembered here. A note is either outstanding, applied or
 * refused; this app can be killed at any point and the next sync will finish
 * what was in flight, because what is in flight is written down where both
 * programs can see it.
 */
class Mailbox(
    private val api: SyncApi,
    private val storage: StorageRepository,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** What leaving a note came to. */
    sealed interface Posted {
        /** The note is with the cartridge. [uid] is what to watch in the PC. */
        data class Left(val note: TransferNote, val uid: String) : Posted
        data class Refused(val reason: String) : Posted
    }

    /**
     * Hands a Pokémon in this app's PC to a cartridge.
     *
     * Nothing is removed here. The Pokémon is marked as promised and stays
     * exactly where it is until the cartridge confirms it has taken it, which
     * is the only order in which a failure cannot lose it: a note that never
     * arrives leaves a Pokémon in the PC, and a note that arrives twice is
     * applied once.
     */
    suspend fun give(uid: String, remote: RemoteSave, target: TransferNote.Target): Posted {
        val stored = storage.get(uid) ?: return Posted.Refused("THAT POKéMON IS NOT IN THE PC.")
        if (stored.inFlight) return Posted.Refused("THAT POKéMON IS ALREADY ON ITS WAY.")

        val note = TransferNote(
            id = UUID.randomUUID().toString(),
            version = remote.version.id,
            playthroughId = remote.playthroughId,
            kind = TransferNote.Kind.GIVE,
            createdAtEpochSeconds = now() / 1000,
            mon = TransferNote.encodeMon(stored.detachedData()),
            to = target,
        )
        // Marked before it is sent. A note that lands while this app is being
        // killed must not leave a Pokémon that looks free to send again.
        storage.mark(uid, note.id)
        return when (val result = api.postNote(note)) {
            is SyncResult.Ok -> Posted.Left(result.value, uid)
            else -> {
                storage.mark(uid, null)
                Posted.Refused(reasonFor(result))
            }
        }
    }

    /**
     * Asks a cartridge for one of the Pokémon in it.
     *
     * The record is copied into the PC now so the player can see where it will
     * land, and marked so nothing can be done with it: the cartridge still has
     * the only one that counts. [save] is what this app last read, and what
     * the note's expectation is taken from — the game checks that the Pokémon
     * in that spot is still this one before it hands it over.
     */
    suspend fun take(
        remote: RemoteSave,
        save: Gen1RecompSave,
        location: SaveLocation,
        targetBox: Int,
    ): Posted {
        val mon = TransferNote.at(save, location)
            ?: return Posted.Refused("THAT POKéMON IS NO LONGER THERE.")

        val note = TransferNote(
            id = UUID.randomUUID().toString(),
            version = remote.version.id,
            playthroughId = remote.playthroughId,
            kind = TransferNote.Kind.TAKE,
            createdAtEpochSeconds = now() / 1000,
            from = location,
            expect = TransferNote.Expectation.of(mon),
        )
        // In the box before it is asked for, so a note that lands while this
        // app is being killed is one the next reconcile can still finish. It
        // is not the player's yet; [StoredPokemon.inFlight] is what says so.
        val stored = storage.deposit(
            data = mon.deepCopy(),
            provenance = Provenance(
                gameVersion = remote.version.id,
                saveId = remote.key,
                savePath = remote.label,
                slotId = remote.slot,
                trainerName = save.trainerName,
                trainerId = save.trainerId,
                playthroughId = remote.playthroughId,
                sourceKind = when (location) {
                    is SaveLocation.Party -> "party"
                    is SaveLocation.Box -> "box"
                },
                sourceIndex = when (location) {
                    is SaveLocation.Party -> location.slot
                    is SaveLocation.Box -> location.box
                },
                depositedAtEpochMillis = now(),
            ),
            preferredBox = targetBox,
        ) ?: return Posted.Refused("STORAGE IS FULL.")
        storage.mark(stored.uid, note.id)

        return when (val result = api.postNote(note)) {
            is SyncResult.Ok -> Posted.Left(result.value, stored.uid)
            else -> {
                storage.withdraw(stored.uid)
                Posted.Refused(reasonFor(result))
            }
        }
    }

    /** What one settled note did to the PC, for whoever is watching. */
    data class Settled(
        val note: TransferNote,
        val uid: String?,
        val name: String,
        val applied: Boolean,
    )

    /**
     * Finishes every note the game has answered.
     *
     * Run on every sync. A note that is still outstanding is left alone; one
     * the game applied or refused is acted on and then cleared from the
     * server, in that order — a crash between the two leaves a note that says
     * what it already did, and doing it again is a no-op, which is the safe
     * way round.
     */
    suspend fun reconcile(): List<Settled> {
        val notes = (api.notes() as? SyncResult.Ok)?.value ?: return emptyList()
        val settled = mutableListOf<Settled>()
        for (note in notes) {
            if (note.status == TransferNote.Status.PENDING) continue
            val stored = storage.all().firstOrNull { it.noteId == note.id }
            val name = stored?.pokemon?.displayName?.uppercase()
                ?: note.expect?.species.orEmpty()
            val applied = note.status == TransferNote.Status.APPLIED
            when {
                // It is the cartridge's now, so it is not ours.
                note.kind == TransferNote.Kind.GIVE && applied ->
                    stored?.let { storage.withdraw(it.uid) }

                // It never left, so it is ours again.
                note.kind == TransferNote.Kind.GIVE ->
                    stored?.let { storage.mark(it.uid, null) }

                // The cartridge has let go, so the copy in the box is real.
                note.kind == TransferNote.Kind.TAKE && applied ->
                    stored?.let { storage.mark(it.uid, null) }

                // It was never ours; take the placeholder back out.
                else -> stored?.let { storage.withdraw(it.uid) }
            }
            if (stored != null || applied) {
                settled += Settled(note, stored?.uid, name, applied)
            }
            api.deleteNote(note.id)
        }
        return settled
    }

    /** Every Pokémon in the PC that is waiting on a note. */
    fun inFlight(): List<StoredPokemon> = storage.all().filter { it.inFlight }

    private fun reasonFor(result: SyncResult<*>): String = when (result) {
        is SyncResult.Failed -> result.message.uppercase()
        SyncResult.Unauthorized -> "THIS DEVICE IS NO LONGER LINKED."
        is SyncResult.Conflict -> "THE CARTRIDGE MOVED ON. TRY AGAIN."
        else -> "THE NOTE COULD NOT BE LEFT."
    }
}
