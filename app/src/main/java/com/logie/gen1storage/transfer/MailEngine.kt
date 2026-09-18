package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.pokemon.Gen2Mail
import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncResult

/**
 * The PC MAILBOX: sending a letter there off a party Pokémon, and attaching
 * one back onto a party Pokémon from it.
 *
 * Both operations stay entirely inside one save — nothing moves into or out
 * of this app's own storage, because nothing holding mail can (see
 * [TransferEngine]'s deposit refusal). So there is no journal here the way
 * [TransferEngine] needs one: a Pokémon never exists twice mid-operation,
 * only the one save's own two tables move in step, in the one commit.
 *
 * What this deliberately leaves out: PUT IN PACK and the TAKE-to-bag path —
 * the two verbs that destroy the message and hand the stationery to the
 * player's own Bag instead. Both need to know whether that Bag's ITEM pocket
 * has a free slot, and this app does not carry pocket data for the item
 * table anywhere yet. Attaching or sending mail never touches the Bag, so
 * neither is blocked by that gap; only those two verbs are missing.
 */
class MailEngine(private val saves: SaveRepository) {

    private fun refuseUnlessWritable(loaded: LoadedSave): TransferResult? =
        if (!loaded.isWritable) {
            TransferResult.Refused("${loaded.remote.version.label} SAVES ARE READ ONLY IN THIS APP.")
        } else null

    /**
     * `SEND MAIL TO PC`: the way out for a Pokémon holding mail, since the PC
     * — this app's, and the cartridge's own boxes — refuses to take one
     * holding any. `MailMenu:sendToPc`.
     */
    suspend fun sendToPc(loaded: LoadedSave, partySlot: Int): TransferResult {
        refuseUnlessWritable(loaded)?.let { return it }
        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")
        val mon = save.party.getOrNull(partySlot - 1)
            ?: return TransferResult.Refused("THAT POKéMON IS NO LONGER THERE")
        if (!mon.holdsMail) return TransferResult.Refused("THAT POKéMON ISN'T HOLDING MAIL")
        if (Gen2Mail.mailboxFull(save.root)) {
            return TransferResult.Refused("YOUR PC'S MAILBOX IS FULL.")
        }

        val mutated = Gen1RecompSave(save.root.deepCopy())
        if (!Gen2Mail.sendToPc(mutated.root, partySlot)) {
            return TransferResult.Refused("THAT POKéMON ISN'T HOLDING MAIL")
        }

        return commit(fresh, mutated, "THE MAIL WAS SENT TO YOUR PC.")
    }

    /**
     * `ATTACH MAIL`: moves a MAILBOX letter onto a party Pokémon, refusing
     * exactly where the cartridge's own loop refuses — an egg, or one
     * already holding something — rather than looping back for another pick,
     * since this app answers a refusal with a message instead of a second
     * chance at the same list. `MailboxMenu:attachMail`.
     */
    suspend fun attachFromMailbox(
        loaded: LoadedSave,
        mailboxIndex: Int,
        partySlot: Int,
    ): TransferResult {
        refuseUnlessWritable(loaded)?.let { return it }
        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")
        val mon = save.party.getOrNull(partySlot - 1)
            ?: return TransferResult.Refused("THAT POKéMON IS NO LONGER THERE")
        if (mon.isEgg) return TransferResult.Refused("AN EGG CAN'T HOLD ANY MAIL.")
        if (mon.heldItem != null) return TransferResult.Refused("IT'S ALREADY HOLDING AN ITEM.")
        if (Gen2Mail.mailbox(save.root).getOrNull(mailboxIndex - 1) == null) {
            return TransferResult.Refused("THAT MAIL IS NO LONGER THERE")
        }

        val mutated = Gen1RecompSave(save.root.deepCopy())
        if (!Gen2Mail.attachFromMailbox(mutated.root, mailboxIndex, partySlot)) {
            return TransferResult.Refused("THAT MAIL IS NO LONGER THERE")
        }

        return commit(fresh, mutated, "THE MAIL WAS MOVED FROM THE MAILBOX.")
    }

    private suspend fun commit(
        fresh: LoadedSave,
        mutated: Gen1RecompSave,
        onSuccess: String,
    ): TransferResult = when (val outcome = saves.commit(fresh, mutated.root)) {
        is CommitOutcome.Committed -> TransferResult.Success(onSuccess, storedUid = null, outcome.after)
        is CommitOutcome.Refused -> TransferResult.Refused(outcome.reason)
        is CommitOutcome.Conflict -> TransferResult.Refused(outcome.reason)
        // Nothing here is duplicated the way a deposit's Pokémon transiently
        // is, so there is no second copy to point at — only the same
        // uncertainty [TransferEngine] hands back for the same reason.
        is CommitOutcome.Unknown -> TransferResult.NeedsRecovery(
            "THE SAVE DID NOT ANSWER. CHECK WHETHER THE MAIL MOVED BEFORE TRYING AGAIN."
        )
    }

    private suspend fun reload(loaded: LoadedSave): LoadedSave? =
        (saves.load(loaded.remote) as? SyncResult.Ok)?.value
}
