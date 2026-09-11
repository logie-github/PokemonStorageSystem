package com.logie.gen1storage.storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Pokemon

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
) {
    val pokemon: Gen1Pokemon get() = Gen1Pokemon(data)

    /** The Generation I data alone, ready to be inserted into a save. */
    fun detachedData(): LuaValue.Table = data.deepCopy()

    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["uid"] = luaStr(uid)
        this["mon"] = data
        this["provenance"] = provenance.toLua()
    }

    companion object {
        fun fromLua(table: LuaValue.Table): StoredPokemon? {
            val uid = table["uid"].asString() ?: return null
            val mon = table["mon"] as? LuaValue.Table ?: return null
            val provenance = (table["provenance"] as? LuaValue.Table)?.let(Provenance::fromLua)
                ?: return null
            return StoredPokemon(uid, mon, provenance)
        }
    }
}

/** One of this app's storage boxes. Modelled on Bill's PC: 12 boxes of 20. */
data class StorageBox(
    val index: Int,
    /** What the player called it, or null if they never did. */
    val name: String?,
    val contents: List<StoredPokemon>,
) {
    /**
     * "BOX 4", or "BOX 4 SHINIES" once it has been named. The number always
     * leads, so a renamed box is still findable by the number it has always
     * had.
     */
    val label: String get() = if (name.isNullOrBlank()) "BOX $index" else "BOX $index $name"

    val isFull: Boolean get() = contents.size >= StorageLayout.BOX_CAPACITY
    val freeSlots: Int get() = StorageLayout.BOX_CAPACITY - contents.size
}

object StorageLayout {
    /** Deliberately the same shape as the Generation I PC. */
    const val BOX_COUNT = 12
    const val BOX_CAPACITY = 20
    const val TOTAL_CAPACITY = BOX_COUNT * BOX_CAPACITY
}
