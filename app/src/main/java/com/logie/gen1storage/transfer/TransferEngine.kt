package com.logie.gen1storage.transfer

import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.gen1recomp.SaveClassifier
import com.logie.gen1storage.gen1recomp.SaveDiscovery
import com.logie.gen1storage.gen1recomp.SaveOrigin
import com.logie.gen1storage.gen1recomp.SaveSource
import com.logie.gen1storage.gen1recomp.SaveWriter
import com.logie.gen1storage.gen1recomp.WriteOutcome
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.saveaccess.SaveVolume
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageRepository
import com.logie.gen1storage.storage.StoredPokemon
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
    data class Success(val message: String, val storedUid: String?) : TransferResult
    data class Refused(val reason: String) : TransferResult
    /**
     * The move stopped part-way and the journal holds the evidence. Neither a
     * duplicate nor a loss has occurred: exactly one side is authoritative and
     * [reason] says what has to happen next.
     */
    data class NeedsRecovery(val reason: String) : TransferResult
}

/** What crash recovery concluded on launch. */
data class RecoveryReport(
    val resolved: List<String>,
    val unresolved: List<String>,
)

/**
 * Moves Pokémon between a Gen1Recomp save and this app's storage.
 *
 * The invariant every step here exists to protect: **before a transfer there is
 * exactly one authoritative copy of a Pokémon, and after it there is exactly
 * one.** Both directions therefore write the destination first and only remove
 * the source once the destination has been read back and re-validated, and both
 * record a journal entry with the save's hash before and after so a crash in
 * between is resolved by evidence rather than by assumption.
 */
class TransferEngine(
    private val volume: SaveVolume,
    private val storage: StorageRepository,
    private val journal: TransferJournal,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val writer = SaveWriter(volume)

    // ------------------------------------------------------------------
    // Deposit: Gen1Recomp save -> this app
    // ------------------------------------------------------------------

    suspend fun deposit(
        source: SaveSource,
        location: SaveLocation,
        expectedFingerprint: String,
        targetBox: Int = 1,
    ): TransferResult {
        journal.read()?.let {
            return TransferResult.Refused("A PREVIOUS TRANSFER IS UNRESOLVED. OPEN SAVE FILES > RECOVERY FIRST.")
        }
        if (!source.writable) return TransferResult.Refused("THIS SAVE IS READ-ONLY")
        if (source.origin != SaveOrigin.MAIN) {
            return TransferResult.Refused("THIS SAVE WAS READ FROM ITS ${source.origin.name} COPY. RESTORE IT FIRST.")
        }

        // 1. Re-read the save now. Anything scanned earlier may be stale.
        val fresh = reread(source) ?: return TransferResult.Refused("SAVE COULD NOT BE READ")
        if (fresh.hash != expectedFingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save

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
        val encoded = LuaText.encode(LuaWriter.encode(mutated.root))
        if (SaveClassifier.classify(encoded).save == null) {
            return TransferResult.Refused("THE RESULTING SAVE WOULD BE INVALID; NOTHING WAS CHANGED")
        }
        val afterHash = SaveDiscovery.sha256(encoded)

        // 5. Journal, then store. From here the Pokémon exists twice and the
        //    journal is what says which copy is authoritative.
        val uid = UUID.randomUUID().toString()
        val entry = TransferEntry(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.DEPOSIT,
            stage = TransferStage.PREPARED,
            uid = uid,
            monFingerprint = removedFingerprint,
            saveId = source.id,
            savePath = source.relativePath,
            saveDirectoryKey = source.directory.key,
            mainName = source.mainName,
            saveHashBefore = fresh.hash,
            saveHashAfter = afterHash,
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
        )
        journal.write(entry)

        val provenance = Provenance(
            gameVersion = source.version.id,
            saveId = source.id,
            savePath = source.relativePath,
            slotId = source.slotId,
            trainerName = save.trainerName,
            trainerId = save.trainerId,
            playthroughId = save.playthroughId,
            sourceKind = entry.sourceKind,
            sourceIndex = entry.sourceIndex,
            depositedAtEpochMillis = now(),
        )
        val stored = storage.deposit(removed, provenance, targetBox, uid)
        if (stored == null) {
            journal.clear()
            return TransferResult.Refused("STORAGE IS FULL")
        }
        journal.write(entry.copy(stage = TransferStage.STORED))

        // 6. Commit the save without the Pokémon.
        return when (val outcome = writer.commit(source, mutated.root, fresh.hash)) {
            is WriteOutcome.Committed -> {
                journal.clear()
                if (!holdsExactlyOne(uid)) {
                    // Never expected: the deposit is committed on both sides,
                    // so say so plainly instead of throwing inside a screen.
                    TransferResult.NeedsRecovery("THE PC DID NOT END UP WITH EXACTLY ONE COPY. CHECK STORAGE BOXES.")
                } else {
                    TransferResult.Success(
                        "${Gen1Pokemon(removed).displayName} WAS STORED IN THE PC.",
                        uid,
                    )
                }
            }
            is WriteOutcome.Refused -> {
                // The save was never touched, so the save is authoritative and
                // the stored copy is the duplicate. Remove it.
                storage.withdraw(uid)
                journal.clear()
                TransferResult.Refused(outcome.reason)
            }
            is WriteOutcome.FailedButRecoverable -> TransferResult.NeedsRecovery(outcome.reason)
        }
    }

    // ------------------------------------------------------------------
    // Withdraw: this app -> Gen1Recomp save
    // ------------------------------------------------------------------

    suspend fun withdraw(
        source: SaveSource,
        uid: String,
        target: WithdrawTarget,
        expectedFingerprint: String,
    ): TransferResult {
        journal.read()?.let {
            return TransferResult.Refused("A PREVIOUS TRANSFER IS UNRESOLVED. OPEN SAVE FILES > RECOVERY FIRST.")
        }
        if (!source.writable) return TransferResult.Refused("THIS SAVE IS READ-ONLY")
        if (source.origin != SaveOrigin.MAIN) {
            return TransferResult.Refused("THIS SAVE WAS READ FROM ITS ${source.origin.name} COPY. RESTORE IT FIRST.")
        }

        val stored = storage.get(uid) ?: return TransferResult.Refused("THAT POKéMON IS NOT IN STORAGE")

        val fresh = reread(source) ?: return TransferResult.Refused("SAVE COULD NOT BE READ")
        if (fresh.hash != expectedFingerprint) {
            return TransferResult.Refused("THE GAME CHANGED THIS SAVE. REFRESH AND TRY AGAIN.")
        }
        val save = fresh.save

        constraintForWithdraw(save, target)?.let { return TransferResult.Refused(it) }

        val mutated = Gen1RecompSave(save.root.deepCopy())
        val data = stored.detachedData()
        val placed = when (target) {
            WithdrawTarget.Party -> mutated.addToParty(data)
            is WithdrawTarget.Box -> mutated.addToBox(target.box, data)
        }
        if (!placed) return TransferResult.Refused("THERE IS NO ROOM FOR IT")

        val encoded = LuaText.encode(LuaWriter.encode(mutated.root))
        if (SaveClassifier.classify(encoded).save == null) {
            return TransferResult.Refused("THE RESULTING SAVE WOULD BE INVALID; NOTHING WAS CHANGED")
        }
        val afterHash = SaveDiscovery.sha256(encoded)

        val entry = TransferEntry(
            id = UUID.randomUUID().toString(),
            kind = TransferKind.WITHDRAW,
            stage = TransferStage.PREPARED,
            uid = uid,
            monFingerprint = Gen1Pokemon(data).fingerprint,
            saveId = source.id,
            savePath = source.relativePath,
            saveDirectoryKey = source.directory.key,
            mainName = source.mainName,
            saveHashBefore = fresh.hash,
            saveHashAfter = afterHash,
            sourceKind = when (target) {
                WithdrawTarget.Party -> Provenance.KIND_PARTY
                is WithdrawTarget.Box -> Provenance.KIND_BOX
            },
            sourceIndex = 0,
            boxIndex = (target as? WithdrawTarget.Box)?.box ?: 0,
            startedAtEpochMillis = now(),
        )
        journal.write(entry)

        return when (val outcome = writer.commit(source, mutated.root, fresh.hash)) {
            is WriteOutcome.Committed -> {
                // The save is verified. Only now does the stored copy go.
                journal.write(entry.copy(stage = TransferStage.SAVED))
                storage.withdraw(uid)
                journal.clear()
                TransferResult.Success(
                    "${stored.pokemon.displayName} WAS TAKEN OUT.",
                    null,
                )
            }
            is WriteOutcome.Refused -> {
                journal.clear()
                TransferResult.Refused(outcome.reason)
            }
            is WriteOutcome.FailedButRecoverable -> TransferResult.NeedsRecovery(outcome.reason)
        }
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    /**
     * Resolves an interrupted transfer using the save's current bytes.
     *
     * There are exactly three answers and no guesswork in any of them: the save
     * still hashes to what it was before the write (the write never landed),
     * it hashes to what it would be after (the write landed), or it hashes to
     * neither — the game wrote it in between, and the app keeps both copies and
     * asks the player rather than deciding.
     */
    suspend fun recover(sources: List<SaveSource>): RecoveryReport {
        val entry = journal.read() ?: return RecoveryReport(emptyList(), emptyList())
        val source = sources.firstOrNull { it.id == entry.saveId }
            ?: return RecoveryReport(
                emptyList(),
                listOf("A ${entry.kind.name.lowercase()} on ${entry.savePath} is unresolved: that save is not visible right now."),
            )

        val fresh = reread(source)
            ?: return RecoveryReport(
                emptyList(),
                listOf("A ${entry.kind.name.lowercase()} on ${entry.savePath} is unresolved: the save cannot be read."),
            )

        val landed = when (fresh.hash) {
            entry.saveHashAfter -> true
            entry.saveHashBefore -> false
            else -> return RecoveryReport(
                emptyList(),
                listOf(
                    "A ${entry.kind.name.lowercase()} on ${entry.savePath} was interrupted and the game has " +
                        "since written that save. Both copies were kept. Check the PC and the save, then " +
                        "clear the record from SAVE FILES."
                ),
            )
        }

        val message: String
        when (entry.kind) {
            TransferKind.DEPOSIT ->
                if (landed) {
                    message = "A deposit completed: ${entry.savePath} no longer holds it and the PC does."
                } else {
                    storage.withdraw(entry.uid)
                    discardStaleStagedFile(source, entry)
                    message = "A deposit was rolled back: the save never changed, so the PC copy was removed."
                }

            TransferKind.WITHDRAW ->
                if (landed) {
                    storage.withdraw(entry.uid)
                    message = "A withdrawal completed: the save holds it and the PC copy was removed."
                } else {
                    discardStaleStagedFile(source, entry)
                    message = "A withdrawal was rolled back: the save never changed, so the PC kept it."
                }
        }
        journal.clear()
        return RecoveryReport(listOf(message), emptyList())
    }

    /** Drops the journal without touching either copy, once a player has looked. */
    fun dismissUnresolved() = journal.clear()

    fun pendingTransfer(): TransferEntry? = journal.read()

    // ------------------------------------------------------------------

    private data class FreshRead(val save: Gen1RecompSave, val hash: String)

    private suspend fun reread(source: SaveSource): FreshRead? {
        val node = volume.child(source.directory, source.mainName)?.takeIf { !it.isDirectory } ?: return null
        val bytes = runCatching { volume.readBytes(node) }.getOrNull() ?: return null
        val save = SaveClassifier.classify(bytes).save ?: return null
        return FreshRead(save, SaveDiscovery.sha256(bytes))
    }

    /**
     * A rolled-back write can leave a `.tmp` witness holding the bytes that
     * were never committed. Upstream's loader promotes a witness when the main
     * file is unreadable, so leaving it would let a rolled-back transfer
     * reappear later. It is removed only when it is provably that witness.
     */
    private suspend fun discardStaleStagedFile(source: SaveSource, entry: TransferEntry) {
        val staged = volume.child(source.directory, source.stagedName)?.takeIf { !it.isDirectory } ?: return
        val bytes = runCatching { volume.readBytes(staged) }.getOrNull() ?: return
        if (SaveDiscovery.sha256(bytes) == entry.saveHashAfter) {
            runCatching { volume.delete(staged) }
        }
    }

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
     * not a state the game can be handed back, so the rule is enforced here too.
     */
    private fun constraintForDeposit(save: Gen1RecompSave, location: SaveLocation): String? = when (location) {
        is SaveLocation.Party ->
            if (save.partyCount <= 1) "YOU CAN'T DEPOSIT THE LAST POKéMON!" else null
        is SaveLocation.Box -> null
    }

    private fun constraintForWithdraw(save: Gen1RecompSave, target: WithdrawTarget): String? = when (target) {
        WithdrawTarget.Party ->
            if (save.partyCount >= Gen1RecompSave.PARTY_MAX) {
                "YOU CAN'T TAKE ANY MORE POKéMON. DEPOSIT POKéMON FIRST."
            } else null
        is WithdrawTarget.Box ->
            if (save.boxFreeSlots(target.box) <= 0) "BOX ${target.box} IS FULL." else null
    }
}

/** A stored Pokémon plus the box it sits in, for list rendering. */
data class StoredEntry(val box: Int, val stored: StoredPokemon)
