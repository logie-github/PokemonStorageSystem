package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.sync.CommitOutcome
import com.logie.gen1storage.sync.LoadedSave
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SaveRepository
import com.logie.gen1storage.sync.SyncResult
import java.util.UUID

/** Which side of a save a Pokémon sits on. */
sealed interface SaveLocation {
    /** 1-based party slot. */
    data class Party(val slot: Int) : SaveLocation
    /** 1-based box number and 1-based position inside it. */
    data class Box(val box: Int, val slot: Int) : SaveLocation
}

/** Where a withdrawn Pokémon should land. */
sealed interface WithdrawTarget {
    data object Party : WithdrawTarget
    data class Box(val box: Int) : WithdrawTarget
}

sealed interface TransferResult {
    data class Success(
        val message: String,
        val storedUid: String?,
        /**
         * The touched save as it now stands, when one was written.
         *
         * Handed back so the caller can hold the cartridge at its new revision
         * without fetching it again: these are the bytes the server just took.
         * Null where nothing was written to a save.
         */
        val after: LoadedSave? = null,
    ) : TransferResult
    data class Refused(val reason: String) : TransferResult

    /**
     * The move stopped part-way and the journal holds the evidence. Neither a
     * duplicate nor a loss has occurred: exactly one side is authoritative and
     * [reason] says what has to happen next.
     */
    data class NeedsRecovery(val reason: String) : TransferResult
}

/** What crash recovery concluded. */
data class RecoveryReport(val resolved: List<String>, val unresolved: List<String>)

/**
 * Moves Pokémon between a synced Gen1Recomp save and this app's storage.
 *
 * The invariant every step protects: **before a transfer there is exactly one
 * authoritative copy of a Pokémon, and after it there is exactly one.** Both
 * directions write the destination first and only remove the source once the
 * destination is confirmed, and both journal the save's hash before and after
 * so an interruption is settled by evidence rather than assumption.
 *
 * Over sync there is one failure mode a local file does not have: a request
 * can fail *after* the server acted, leaving the outcome genuinely unknown.
 * That is what [CommitOutcome.Unknown] means, and why recovery re-reads the
 * save and compares hashes instead of trusting how far the code got.
 */
class TransferEngine(
    private val saves: SaveRepository,
    private val storage: StorageRepository,
    private val journal: TransferJournal,
    /**
     * What the app believes it has already handed out. Consulted before a
     * withdrawal so the same Pokémon cannot be written into a second cartridge.
     */
    private val ledger: PlacementLedger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ------------------------------------------------------------------
    // Deposit: synced save -> this app
    // ------------------------------------------------------------------

    suspend fun deposit(
        loaded: LoadedSave,
        location: SaveLocation,
        targetBox: Int = 1,
    ): TransferResult {
        if (!loaded.isWritable) {
            return TransferResult.Refused(
                "${loaded.remote.version.label} SAVES ARE READ ONLY IN THIS APP."
            )
        }
        journal.read()?.let {
            return TransferResult.Refused("A PREVIOUS TRANSFER IS UNRESOLVED. SYNC FIRST.")
        }

        // 1. Re-read now: anything listed or opened earlier may be stale.
        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")

        // 2. Confirm the selected Pokémon is where the app thinks it is.
        val selected = pokemonAt(save, location)
            ?: return TransferResult.Refused("THAT POKéMON IS NO LONGER THERE")

        // 3. Constraint checks, before anything is written.
        constraintForDeposit(save, location)?.let { return TransferResult.Refused(it) }

        // 4. Build the post-transfer save and hash it.
        val mutated = Gen1RecompSave(save.root.deepCopy())
        val removed = when (location) {
            is SaveLocation.Party -> mutated.removeFromParty(location.slot)
            is SaveLocation.Box -> mutated.removeFromBox(location.box, location.slot)
        } ?: return TransferResult.Refused("THAT POKéMON IS NO LONGER THERE")

        val removedFingerprint = Gen1Pokemon(removed).fingerprint
        if (removedFingerprint != selected.fingerprint) {
            return TransferResult.Refused("THE SELECTED POKéMON DID NOT MATCH; NOTHING WAS CHANGED")
        }
        val encoded = LuaWriter.encode(mutated.root)
        if (SaveClassifier.classify(encoded).save == null) {
            return TransferResult.Refused("THE RESULTING SAVE WOULD BE INVALID; NOTHING WAS CHANGED")
        }

        // 5. Journal, then store. From here the Pokémon exists twice and the
        //    journal is what says which copy is authoritative.
        val uid = UUID.randomUUID().toString()
        val entry = journalEntry(
            kind = TransferKind.DEPOSIT,
            uid = uid,
            monFingerprint = removedFingerprint,
            loaded = fresh,
            before = fresh.fingerprint,
            after = SaveRepository.sha256(encoded),
            location = location,
        )
        journal.write(entry)

        val stored = storage.deposit(removed, provenanceOf(fresh, save, entry), targetBox, uid)
        if (stored == null) {
            journal.clear()
            return TransferResult.Refused("STORAGE IS FULL")
        }
        journal.write(entry.copy(stage = TransferStage.STORED))

        // 6. Upload the save without the Pokémon.
        return when (val outcome = saves.commit(fresh, mutated.root)) {
            is CommitOutcome.Committed -> {
                journal.clear()
                // It has left that cartridge, so the app is no longer holding
                // it there and a later withdrawal has nothing to refuse. Only
                // that cartridge's record goes: see [PlacementLedger.forgetFrom].
                //
                // Bookkeeping, and bookkeeping never decides a transfer. The
                // write has landed by this point; a ledger that could not be
                // updated must not turn a move that happened into a failure
                // report.
                runCatching { ledger.forgetFrom(removedFingerprint, fresh.key) }
                if (!holdsExactlyOne(uid)) {
                    TransferResult.NeedsRecovery("THE PC DID NOT END UP WITH EXACTLY ONE COPY. CHECK STORAGE BOXES.")
                } else {
                    TransferResult.Success(
                        "${Gen1Pokemon(removed).displayName.uppercase()} was stored in BOX $targetBox.",
                        uid,
                        outcome.after,
                    )
                }
            }
            // Refused and Conflict both mean the server did not take the write,
            // so the save is authoritative and the stored copy is the duplicate.
            is CommitOutcome.Refused -> rollBackDeposit(uid, outcome.reason)
            is CommitOutcome.Conflict -> rollBackDeposit(uid, outcome.reason)
            is CommitOutcome.Unknown -> TransferResult.NeedsRecovery(outcome.reason)
        }
    }

    private fun rollBackDeposit(uid: String, reason: String): TransferResult {
        storage.withdraw(uid)
        journal.clear()
        return TransferResult.Refused(reason)
    }

    // ------------------------------------------------------------------
    // Withdraw: this app -> synced save
    // ------------------------------------------------------------------

    suspend fun withdraw(
        loaded: LoadedSave,
        uid: String,
        target: WithdrawTarget,
    ): TransferResult {
        if (!loaded.isWritable) {
            return TransferResult.Refused(
                "${loaded.remote.version.label} SAVES ARE READ ONLY IN THIS APP."
            )
        }
        journal.read()?.let {
            return TransferResult.Refused("A PREVIOUS TRANSFER IS UNRESOLVED. SYNC FIRST.")
        }
        val stored = storage.get(uid) ?: return TransferResult.Refused("THAT POKéMON IS NOT IN STORAGE")

        val fresh = reload(loaded) ?: return TransferResult.Refused("THE SAVE COULD NOT BE READ")
        if (fresh.fingerprint != loaded.fingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save ?: return TransferResult.Refused("THAT SAVE CANNOT BE READ")

        constraintForWithdraw(save, target)?.let { return TransferResult.Refused(it) }

        // The one write that can put a Pokémon in two places at once, so it is
        // the one that checks. Both halves matter: a save restored from a
        // backup can already hold it, and a cartridge this app wrote it to
        // earlier can still be holding it even while nothing is loaded from
        // that cartridge now.
        val fingerprint = stored.pokemon.fingerprint
        if (alreadyHolds(save, fingerprint)) {
            return TransferResult.Refused(
                "${stored.pokemon.displayName.uppercase()} IS ALREADY IN THAT SAVE."
            )
        }
        runCatching { ledger.placement(fingerprint) }.getOrNull()?.let { held ->
            if (held.saveKey != fresh.key) {
                return TransferResult.Refused(
                    "THE PC PUT ${stored.pokemon.displayName.uppercase()} IN ${held.savePath.uppercase()}. " +
                        "TAKE IT OUT OF THERE FIRST."
                )
            }
        }

        val mutated = Gen1RecompSave(save.root.deepCopy())
        val data = stored.detachedData()
        val placed = when (target) {
            WithdrawTarget.Party -> mutated.addToParty(data)
            is WithdrawTarget.Box -> mutated.addToBox(target.box, data)
        }
        if (!placed) return TransferResult.Refused("THERE IS NO ROOM FOR IT")

        val encoded = LuaWriter.encode(mutated.root)
        if (SaveClassifier.classify(encoded).save == null) {
            return TransferResult.Refused("THE RESULTING SAVE WOULD BE INVALID; NOTHING WAS CHANGED")
        }

        val entry = journalEntry(
            kind = TransferKind.WITHDRAW,
            uid = uid,
            monFingerprint = Gen1Pokemon(data).fingerprint,
            loaded = fresh,
            before = fresh.fingerprint,
            after = SaveRepository.sha256(encoded),
            location = when (target) {
                WithdrawTarget.Party -> SaveLocation.Party(0)
                is WithdrawTarget.Box -> SaveLocation.Box(target.box, 0)
            },
        )
        journal.write(entry)

        return when (val outcome = saves.commit(fresh, mutated.root)) {
            is CommitOutcome.Committed -> {
                // The save has it. Only now does the stored copy go.
                journal.write(entry.copy(stage = TransferStage.SAVED))
                storage.withdraw(uid)
                journal.clear()
                // Again bookkeeping, and again after the fact: the save has
                // it either way.
                runCatching {
                    ledger.record(
                        Placement(
                            fingerprint = fingerprint,
                            saveKey = fresh.key,
                            savePath = entry.savePath,
                            monName = stored.pokemon.displayName,
                            atMillis = now(),
                        )
                    )
                }
                TransferResult.Success(
                    "${stored.pokemon.displayName.uppercase()} is taken out.",
                    null,
                    outcome.after,
                )
            }
            is CommitOutcome.Refused -> { journal.clear(); TransferResult.Refused(outcome.reason) }
            is CommitOutcome.Conflict -> { journal.clear(); TransferResult.Refused(outcome.reason) }
            is CommitOutcome.Unknown -> TransferResult.NeedsRecovery(outcome.reason)
        }
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    /**
     * Resolves an interrupted transfer from the save's current bytes.
     *
     * Three answers, no guesswork: the save still hashes to what it was before
     * the write (it never landed), to what it would be after (it landed), or to
     * neither — the game saved in between, in which case both copies are kept
     * and the player is told.
     */
    suspend fun recover(known: List<RemoteSave>): RecoveryReport {
        val entry = journal.read() ?: return RecoveryReport(emptyList(), emptyList())
        val remote = known.firstOrNull { it.key == entry.saveId }
            ?: return RecoveryReport(
                emptyList(),
                listOf(
                    "A ${entry.kind.name.lowercase()} on ${entry.savePath} is unresolved: " +
                        "that save is not on the account right now, so both copies were kept."
                ),
            )

        val current = saves.currentFingerprint(remote)
            ?: return RecoveryReport(
                emptyList(),
                listOf(
                    "A ${entry.kind.name.lowercase()} on ${entry.savePath} is unresolved: " +
                        "the save could not be read, so both copies were kept."
                ),
            )

        val landed = when (current) {
            entry.saveHashAfter -> true
            entry.saveHashBefore -> false
            else -> return RecoveryReport(
                emptyList(),
                listOf(
                    "A ${entry.kind.name.lowercase()} on ${entry.savePath} was interrupted and the " +
                        "game has since saved over it. Both copies were kept. Check the PC and the " +
                        "save."
                ),
            )
        }

        val message = when (entry.kind) {
            TransferKind.DEPOSIT ->
                if (landed) {
                    "A deposit completed: ${entry.savePath} no longer holds it and the PC does."
                } else {
                    storage.withdraw(entry.uid)
                    "A deposit was rolled back: the save never changed, so the PC copy was removed."
                }

            TransferKind.WITHDRAW ->
                if (landed) {
                    storage.withdraw(entry.uid)
                    "A withdrawal completed: the save holds it and the PC copy was removed."
                } else {
                    "A withdrawal was rolled back: the save never changed, so the PC kept it."
                }
        }
        journal.clear()
        return RecoveryReport(listOf(message), emptyList())
    }

    /** Drops the journal without touching either copy, once a player has looked. */
    fun dismissUnresolved() = journal.clear()

    fun pendingTransfer(): TransferEntry? = journal.read()

    // ------------------------------------------------------------------

    private suspend fun reload(loaded: LoadedSave): LoadedSave? =
        (saves.load(loaded.remote) as? SyncResult.Ok)?.value

    private fun journalEntry(
        kind: TransferKind,
        uid: String,
        monFingerprint: String,
        loaded: LoadedSave,
        before: String,
        after: String,
        location: SaveLocation,
    ) = TransferEntry(
        id = UUID.randomUUID().toString(),
        kind = kind,
        stage = TransferStage.PREPARED,
        uid = uid,
        monFingerprint = monFingerprint,
        saveId = loaded.key,
        savePath = "${loaded.remote.version.label} ${loaded.remote.label}",
        saveDirectoryKey = loaded.remote.playthroughId,
        mainName = loaded.remote.slot ?: loaded.remote.playthroughId,
        saveHashBefore = before,
        saveHashAfter = after,
        sourceKind = when (location) {
            is SaveLocation.Party -> Provenance.KIND_PARTY
            is SaveLocation.Box -> Provenance.KIND_BOX
        },
        sourceIndex = when (location) {
            is SaveLocation.Party -> location.slot
            is SaveLocation.Box -> location.slot
        },
        boxIndex = (location as? SaveLocation.Box)?.box ?: 0,
        startedAtEpochMillis = now(),
        baseRev = loaded.rev,
    )

    private fun provenanceOf(loaded: LoadedSave, save: Gen1RecompSave, entry: TransferEntry) =
        Provenance(
            gameVersion = loaded.remote.version.id,
            saveId = loaded.key,
            savePath = entry.savePath,
            slotId = loaded.remote.slot,
            trainerName = save.trainerName,
            trainerId = save.trainerId,
            playthroughId = loaded.remote.playthroughId,
            sourceKind = entry.sourceKind,
            sourceIndex = entry.sourceIndex,
            depositedAtEpochMillis = now(),
        )

    /**
     * Whether [save] already holds a Pokémon identical to this one.
     *
     * Content, not identity: two Pokémon that fingerprint the same are the same
     * Pokémon as far as anything can tell, including the cartridge. A genuine
     * coincidence — two untouched Pokémon of the same species, level, DVs,
     * moves, PP and OT — would be refused as well, which costs a player one
     * deposit of an interchangeable Pokémon and is the safe side to err on.
     */
    private fun alreadyHolds(save: Gen1RecompSave, fingerprint: String): Boolean =
        save.party.any { it.fingerprint == fingerprint } ||
            save.boxes.any { box -> box.any { it.fingerprint == fingerprint } }

    /** What the app believes it has put where, for the save files screen. */
    fun placements(): List<Placement> = ledger.all()

    /** Drops one record, for a player who knows the cartridge no longer holds it. */
    fun forgetPlacement(fingerprint: String) = ledger.forget(fingerprint)

    /** The no-duplication invariant, checked rather than assumed. */
    private fun holdsExactlyOne(uid: String): Boolean =
        storage.state().boxes.sumOf { box -> box.contents.count { it.uid == uid } } == 1

    private fun pokemonAt(save: Gen1RecompSave, location: SaveLocation): Gen1Pokemon? = when (location) {
        is SaveLocation.Party -> save.party.getOrNull(location.slot - 1)
        is SaveLocation.Box -> save.boxes.getOrNull(location.box - 1)?.getOrNull(location.slot - 1)
    }

    /**
     * Upstream `BoxMenu.deposit` refuses to store the last party Pokémon
     * ("You can't deposit the last POKéMON!"). A save with an empty party is
     * not a state the game can be handed back, so the rule holds here too.
     */
    private fun constraintForDeposit(save: Gen1RecompSave, location: SaveLocation): String? =
        when (location) {
            is SaveLocation.Party ->
                if (save.partyCount <= 1) "YOU CAN'T DEPOSIT THE LAST POKéMON!" else null
            is SaveLocation.Box -> null
        }

    private fun constraintForWithdraw(save: Gen1RecompSave, target: WithdrawTarget): String? =
        when (target) {
            WithdrawTarget.Party ->
                if (save.partyCount >= Gen1RecompSave.PARTY_MAX) {
                    "YOU CAN'T TAKE ANY MORE POKéMON. DEPOSIT POKéMON FIRST."
                } else null
            is WithdrawTarget.Box ->
                if (save.boxFreeSlots(target.box) <= 0) "BOX ${target.box} IS FULL." else null
        }
}
