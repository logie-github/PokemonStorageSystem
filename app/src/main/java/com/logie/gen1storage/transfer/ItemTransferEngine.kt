package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1Items
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.storage.ItemRepository
import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncResult

/**
 * Items between a save's item PC and this app's.
 *
 * Deliberately simpler than [TransferEngine], because an item is not a
 * Pokémon: it has no identity to keep track of, so there is nothing a journal
 * could tell us afterwards that counting cannot. What is kept is the ordering.
 * Each direction adds the item to its destination **before** taking it from
 * its source, so the window a crash could land in is one where the item exists
 * twice rather than one where it exists nowhere. A duplicate is something a
 * player can throw away; a loss is not something they can undo.
 */
class ItemTransferEngine(
    private val saves: SaveRepository,
    private val items: ItemRepository,
) {

    /**
     * Save's item PC -> this app.
     *
     * The app's copy is written first, then the save is committed without it.
     * A commit that fails takes the app's copy back out, so a refusal leaves
     * both sides exactly as they were.
     */
    suspend fun deposit(loaded: LoadedSave, id: String, count: Int): TransferResult {
        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")

        val available = save.pcItems.firstOrNull { it.id == id }?.count ?: 0
        if (available <= 0) return TransferResult.Refused("THAT ITEM IS NO LONGER THERE")
        val wanted = count.coerceIn(1, available)

        val taken = items.add(id, wanted)
        if (taken <= 0) return TransferResult.Refused("THERE IS NO ROOM FOR IT")

        val mutated = Gen1RecompSave(save.root.deepCopy())
        Gen1Items.remove(mutated.ensurePcItems(), id, taken)

        return when (val outcome = saves.commit(fresh, mutated.root)) {
            is CommitOutcome.Committed -> TransferResult.Success(
                "${label(id)} x$taken went into the PC.",
                storedUid = null,
                after = outcome.after,
            )
            // Nothing was written, so the app's copy is the only one that
            // moved and it goes straight back.
            is CommitOutcome.Refused -> {
                items.remove(id, taken)
                TransferResult.Refused(outcome.reason)
            }
            is CommitOutcome.Conflict -> {
                items.remove(id, taken)
                TransferResult.Refused(outcome.reason)
            }
            // The write may or may not have landed. The app's copy stays: at
            // worst there are two, and the player can see both.
            is CommitOutcome.Unknown -> TransferResult.NeedsRecovery(
                "THE SAVE DID NOT ANSWER. ${label(id)} MAY BE IN BOTH PLACES."
            )
        }
    }

    /**
     * This app -> the save's item PC.
     *
     * The save is committed with the item added first; only once that is
     * confirmed does the app let go of its own copy.
     */
    suspend fun withdraw(loaded: LoadedSave, id: String, count: Int): TransferResult {
        val held = items.count(id)
        if (held <= 0) return TransferResult.Refused("THAT ITEM IS NOT IN THE PC")

        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")

        val mutated = Gen1RecompSave(save.root.deepCopy())
        val moved = Gen1Items.add(mutated.ensurePcItems(), id, count.coerceIn(1, held))
        if (moved <= 0) return TransferResult.Refused("THERE IS NO ROOM FOR IT")

        return when (val outcome = saves.commit(fresh, mutated.root)) {
            is CommitOutcome.Committed -> {
                items.remove(id, moved)
                TransferResult.Success(
                    "${label(id)} x$moved went into the save.",
                    storedUid = null,
                    after = outcome.after,
                )
            }
            is CommitOutcome.Refused -> TransferResult.Refused(outcome.reason)
            is CommitOutcome.Conflict -> TransferResult.Refused(outcome.reason)
            // It may have landed. The app keeps its copy rather than dropping
            // one that might not have arrived.
            is CommitOutcome.Unknown -> TransferResult.NeedsRecovery(
                "THE SAVE DID NOT ANSWER. ${label(id)} MAY BE IN BOTH PLACES."
            )
        }
    }

    private suspend fun reload(loaded: LoadedSave): LoadedSave? =
        (saves.load(loaded.remote) as? SyncResult.Ok)?.value

    private fun label(id: String) = id.replace('_', ' ')
}
