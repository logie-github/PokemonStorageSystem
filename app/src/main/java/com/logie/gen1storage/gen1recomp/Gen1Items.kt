package com.logie.gen1storage.gen1recomp

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.luaNum

/**
 * One kind of item and how many of it there are.
 *
 * Items are counts against a name rather than a list of things, which is how
 * the save writes them and how the games think of them: twenty Potions is one
 * entry, not twenty.
 */
data class ItemStack(
    val id: String,
    val count: Int,
    /**
     * Which generation's vocabulary this stack is named in.
     *
     * Generation I and Generation II do not agree about what an item id
     * means — a Generation II TM is not a Generation I one, and LEFTOVERS is
     * not a word Red's inventory table has any use for — so a stack read out
     * of a save carries the generation that save is, the same way a stored
     * Pokémon carries its own.
     */
    val generation: Int = 1,
) {
    /** `ULTRA_BALL` as the screen says it. */
    val label: String get() = id.replace('_', ' ')
}

/** How high one entry may go, as the games cap a stack. */
const val MAX_ITEM_COUNT = 99

/**
 * Items in a Gen1Recomp save.
 *
 * Two places hold them: `inventory`, which is the bag the player is carrying,
 * and `pcItems`, which is the PC in their bedroom. Badges live in `inventory`
 * too, so they are filtered out — a badge is not something to move about.
 */
object Gen1Items {

    fun read(table: LuaValue.Table?, generation: Int = 1): List<ItemStack> =
        table?.entries().orEmpty().mapNotNull { (key, value) ->
            val id = (key as? LuaKey.Name)?.value ?: return@mapNotNull null
            // Both regions': a Gold save's badges live in the same table its
            // items do, and a ZEPHYRBADGE is no more a thing to move about
            // than a BOULDERBADGE is.
            if (id in Gen1RecompSave.BADGE_IDS || id in Gen1RecompSave.JOHTO_BADGE_IDS) {
                return@mapNotNull null
            }
            val count = value.asInt() ?: return@mapNotNull null
            if (count <= 0) null else ItemStack(id, count, generation)
        }.sortedBy { it.id }

    /**
     * Adds [count] of [id] to a table, capped the way a stack is.
     *
     * Returns how many actually went in, so a transfer that could only take
     * part of a stack says so rather than quietly dropping the rest.
     */
    fun add(table: LuaValue.Table, id: String, count: Int): Int {
        if (count <= 0) return 0
        val existing = table[id].asInt() ?: 0
        val room = (MAX_ITEM_COUNT - existing).coerceAtLeast(0)
        val moved = minOf(count, room)
        if (moved > 0) table[id] = luaNum((existing + moved).toDouble())
        return moved
    }

    /** Takes [count] of [id] away, removing the entry once it is empty. */
    fun remove(table: LuaValue.Table, id: String, count: Int): Int {
        if (count <= 0) return 0
        val existing = table[id].asInt() ?: return 0
        val taken = minOf(count, existing)
        val left = existing - taken
        if (left > 0) table[id] = luaNum(left.toDouble()) else table.remove(LuaKey.Name(id))
        return taken
    }
}
