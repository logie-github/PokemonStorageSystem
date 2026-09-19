package com.logie.gen1storage.storage

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.TimeCapsule

/**
 * Where a stored Pokémon came from.
 *
 * Provenance belongs to this app, never to the Pokémon. It is stored alongside
 * the Generation I data, never inside it, so a Pokémon withdrawn back into a
 * save carries exactly the fields it arrived with.
 */
data class Provenance(
    val gameVersion: String,
    val saveId: String,
    val savePath: String,
    val slotId: String?,
    val trainerName: String,
    val trainerId: Int?,
    val playthroughId: String?,
    val sourceKind: String,
    val sourceIndex: Int,
    val depositedAtEpochMillis: Long,
) {
    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["gameVersion"] = luaStr(gameVersion)
        this["saveId"] = luaStr(saveId)
        this["savePath"] = luaStr(savePath)
        slotId?.let { this["slotId"] = luaStr(it) }
        this["trainerName"] = luaStr(trainerName)
        trainerId?.let { this["trainerId"] = luaNum(it) }
        playthroughId?.let { this["playthroughId"] = luaStr(it) }
        this["sourceKind"] = luaStr(sourceKind)
        this["sourceIndex"] = luaNum(sourceIndex)
        this["depositedAt"] = luaNum(depositedAtEpochMillis.toDouble())
    }

    companion object {
        const val KIND_PARTY = "party"
        const val KIND_BOX = "box"

        fun fromLua(table: LuaValue.Table): Provenance = Provenance(
            gameVersion = table["gameVersion"].asString().orEmpty(),
            saveId = table["saveId"].asString().orEmpty(),
            savePath = table["savePath"].asString().orEmpty(),
            slotId = table["slotId"].asString(),
            trainerName = table["trainerName"].asString().orEmpty(),
            trainerId = table["trainerId"].asInt(),
            playthroughId = table["playthroughId"].asString(),
            sourceKind = table["sourceKind"].asString() ?: KIND_BOX,
            sourceIndex = table["sourceIndex"].asInt() ?: 0,
            depositedAtEpochMillis = table["depositedAt"].asInt()?.toLong()
                ?: (table["depositedAt"] as? LuaValue.Num)?.value?.toLong() ?: 0L,
        )
    }
}

/**
 * One Pokémon in this app's own PC, with the raw Generation I table untouched.
 *
 * [uid] is this app's handle on the Pokémon and never reaches a save file.
 */
data class StoredPokemon(
    val uid: String,
    val data: LuaValue.Table,
    val provenance: Provenance,
    /**
     * The note this one is waiting on, where it is in the middle of a move.
     *
     * A transfer asks the game to change its own save and then waits to hear
     * that it did. For as long as that is outstanding this Pokémon is either
     * on its way out — still this app's, but promised to a cartridge — or on
     * its way in, shown in the box it will land in but still the cartridge's
     * until the cartridge says otherwise. Either way it is not a Pokémon the
     * player can act on, and [Mailbox] is what clears it.
     */
    val noteId: String? = null,
    /**
     * Which generation's tables describe this one.
     *
     * A fact about the Pokémon rather than about the save it came from, and
     * kept beside the raw table rather than inside it: a Pokémon withdrawn
     * back into a cartridge carries exactly the fields it arrived with, and
     * this is the app's bookkeeping. Set once, at [StorageRepository.deposit],
     * to whichever generation the save it came out of is. The one way it
     * changes after that is [TimeCapsule.carry], because moving a Pokémon
     * forward on its own — rather than as a side effect of where it happened
     * to be deposited from — is something a player does on purpose.
     */
    val generation: Int = 1,
    /** What it was before it went through the Time Capsule, if it has. */
    val timeCapsule: TimeCapsule.Record? = null,
    /**
     * The app's own mark on this Pokémon, and everywhere it has been.
     *
     * Kept beside the table like everything else of the app's, and written
     * *into* the table only on the way out — see [detachedData]. See
     * [Lineage] for why a mark exists at all when there is already a content
     * fingerprint.
     */
    val lineage: Lineage? = null,
) {
    val pokemon: Gen1Pokemon get() = Gen1Pokemon(data, generation)

    /**
     * Which game's sprite art to draw this Pokémon under.
     *
     * Ordinarily the game it was deposited from — [provenance]'s
     * `gameVersion`, which never changes once a Pokémon is in the PC. A Time
     * Capsule crossing is the one thing that leaves that stale: the record
     * still names the Generation I cartridge it left, but [generation] has
     * moved to II and the species field it is drawn from is spelled
     * Generation II's way wherever the two differ — MR_MIME becomes
     * MR__MIME — a spelling Generation I's own sprite sets have never heard.
     * Once the recorded game's generation no longer matches this Pokémon's
     * own, the art follows the Pokémon forward instead of staying where it
     * was deposited from.
     */
    val spriteGameVersionId: String?
        get() {
            val recorded = GameVersion.fromId(provenance.gameVersion)
            return when {
                recorded == null || recorded.generation == generation -> provenance.gameVersion
                // The Generation II card it was carried into, which is the
                // one the player was actually holding. Gold only for a record
                // written before that was kept.
                generation == 2 -> timeCapsule?.gameVersion ?: GameVersion.GOLD.id
                else -> provenance.gameVersion
            }
        }

    /** Whether this one is mid-move and cannot be sent anywhere else. */
    val inFlight: Boolean get() = noteId != null

    /**
     * The Generation I data alone, ready to be inserted into a save — with
     * the app's tag on it, so the same Pokémon is recognisable when it comes
     * back however much it has been played with in between.
     */
    fun detachedData(): LuaValue.Table = data.deepCopy().let { copy ->
        lineage?.let { Lineage.stamp(copy, it.tag) } ?: copy
    }

    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["uid"] = luaStr(uid)
        this["mon"] = data
        this["provenance"] = provenance.toLua()
        noteId?.let { this["note"] = luaStr(it) }
        if (generation != 1) this["generation"] = luaNum(generation)
        timeCapsule?.let { this["timeCapsule"] = it.toLua() }
        lineage?.let { this["lineage"] = it.toLua() }
    }

    companion object {
        fun fromLua(table: LuaValue.Table): StoredPokemon? {
            val uid = table["uid"].asString() ?: return null
            val mon = table["mon"] as? LuaValue.Table ?: return null
            val provenance = (table["provenance"] as? LuaValue.Table)?.let(Provenance::fromLua)
                ?: return null
            return StoredPokemon(
                uid = uid,
                data = mon,
                provenance = provenance,
                noteId = table["note"].asString(),
                // Written down since the Time Capsule existed; before that
                // everything in this PC came out of a Generation I cartridge,
                // which is what the default says.
                generation = table["generation"].asInt() ?: 1,
                timeCapsule = (table["timeCapsule"] as? LuaValue.Table)
                    ?.let(TimeCapsule.Record::fromLua),
                // Absent on anything deposited before the app started
                // marking them; [StorageRepository.deposit] gives one to
                // every Pokémon it sees from here on.
                lineage = (table["lineage"] as? LuaValue.Table)?.let(Lineage::fromLua),
            )
        }
    }
}

/** This app's storage box: one grid, six across and a hundred down. */
data class StorageBox(
    val index: Int,
    /** What the player called it, or null if they never did. */
    val name: String?,
    /**
     * Every spot in the box, in place, null where nothing is sitting. A box
     * is a grid a player arranges rather than a list that closes up behind
     * what leaves it, so an empty spot in the middle is a real thing and has
     * to survive being written down.
     */
    val slots: List<StoredPokemon?>,
) {
    /** What is actually in the box, in reading order. */
    val contents: List<StoredPokemon> get() = slots.filterNotNull()

    /**
     * "THE PC", or whatever the player has called it.
     *
     * There is one box and it is the whole of this machine's storage, so it is
     * named for the machine. A number would only raise the question of where
     * the others are, and "the box" says less than "the PC" about what it is.
     */
    val label: String get() = if (name.isNullOrBlank()) "THE PC" else name.uppercase()

    val isFull: Boolean get() = slots.none { it == null }
    val freeSlots: Int get() = slots.count { it == null }
}

/**
 * The shape of this app's PC: one box, six across and a hundred down.
 *
 * It was twelve boxes of thirty, the cartridge's own arrangement, and that is
 * the wrong shape for a machine whose whole job is to hold what the cartridges
 * cannot. Twelve boxes meant choosing one on the way in, remembering which one
 * a Pokémon went to, and moving things between them by hand. One long box that
 * scrolls is the same six hundred spots with none of that: everything arrives
 * in the same place and is always where it was left.
 *
 * Anything a twelve-box file held still fits — 12 x 30 is 360, and this is
 * 600 — so the change costs nothing that was already stored.
 */
object StorageLayout {
    const val BOX_COUNT = 1

    /** The one box, which everything in this app lives in. */
    const val THE_BOX = 1

    /** The grid the box is drawn as, and so the shape it is stored in. */
    const val BOX_COLUMNS = 6
    const val BOX_ROWS = 100
    const val BOX_CAPACITY = BOX_COLUMNS * BOX_ROWS
    const val TOTAL_CAPACITY = BOX_COUNT * BOX_CAPACITY
}
