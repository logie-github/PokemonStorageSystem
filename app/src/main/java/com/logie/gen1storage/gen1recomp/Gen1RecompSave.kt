package com.logie.gen1storage.gen1recomp

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asBoolean
import com.logie.gen1storage.lua.asDouble
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.pokemon.Gen1Pokemon
import com.logie.gen1storage.pokemon.Gen1Stats

/** The three Generation I games this app supports. Nothing else is in scope. */
enum class GameVersion(val id: String, val label: String, val saveSuffix: String) {
    RED("red", "RED", ""),
    BLUE("blue", "BLUE", "_blue"),
    YELLOW("yellow", "YELLOW", "_yellow");

    companion object {
        fun fromId(id: String?): GameVersion? = entries.firstOrNull { it.id == id?.lowercase() }

        /**
         * Generation II ids upstream also knows. They are recognised only so a
         * Gold/Silver/Crystal save can be reported as out of scope rather than
         * silently misread as Generation I.
         */
        val GEN2_IDS = setOf("gold", "silver", "crystal")
    }
}

/**
 * A parsed Gen1Recomp progress file.
 *
 * Structure per upstream `src/core/SaveData.lua` and `src/pokemon/Boxes.lua`:
 * `party` is a 1..n array of Pokémon tables (max 6), PC storage is `boxes`
 * (12 arrays of up to 20) with pre-12-box saves carrying a single `box` list,
 * `currentBox` selects the active box, and `player` holds the trainer.
 *
 * A Pokémon table is the *same shape* in the party and in a box: upstream
 * deposit is `table.remove(party, i)` followed by `table.insert(box, mon)`
 * (`src/ui/BoxMenu.lua`). The only asymmetry is that a box Pokémon may lack a
 * stat block, which `Stats.ensure` fills when it returns to a party.
 */
class Gen1RecompSave(val root: LuaValue.Table) {

    val version: GameVersion?
        get() = GameVersion.fromId(root["version"].asString())
            // Saves written before Blue support existed carry no `version` tag
            // and Red was the only game that had shipped, so upstream core
            // migration 2 defaults every untagged save to Red.
            ?: if (root[LuaKey.Name("version")] == null && !isGen2) GameVersion.RED else null

    val isGen2: Boolean
        get() = root["generation"].asInt() == 2 ||
            root["version"].asString()?.lowercase() in GameVersion.GEN2_IDS

    private val player: LuaValue.Table? get() = root["player"].asTable()

    val trainerName: String get() = player?.get("name").asString()?.let(LuaText::displayText) ?: "?"
    val trainerId: Int? get() = player?.get("id").asInt()
    val rivalName: String? get() = player?.get("rival").asString()?.let(LuaText::displayText)
    val currentMap: String? get() = player?.get("map").asString()

    /** `save.playTime` is a plain seconds accumulator in a Generation I save. */
    val playTimeSeconds: Double get() = root["playTime"].asDouble() ?: 0.0

    /** Play time as the save screen says it: so many hours, so many minutes. */
    val playTimeLongText: String
        get() {
            val total = playTimeSeconds.toLong()
            return "${total / 3600} HOURS ${(total % 3600) / 60} MINUTES"
        }

    val playTimeText: String
        get() {
            val total = playTimeSeconds.toLong()
            return "%d:%02d".format(total / 3600, (total / 60) % 60)
        }

    /** `Badges.count`: a truthy `save.inventory[<badge id>]` entry per gym. */
    val badgeCount: Int
        get() {
            val inventory = root["inventory"].asTable() ?: return 0
            return BADGE_IDS.count { inventory[it] != null }
        }

    val dexOwnedCount: Int
        get() = root["pokedex"].asTable()?.get("owned").asTable()
            ?.entries().orEmpty().count { it.second.asBoolean() != false }

    val playthroughId: String? get() = root["meta"].asTable()?.get("playthroughId").asString()
    val saveFormat: Int? get() = root["meta"].asTable()?.get("format").asInt()

    // ------- items

    /** The bag the player is carrying, badges left out of it. */
    val bag: List<ItemStack> get() = Gen1Items.read(root["inventory"].asTable())

    /** The PC in the player's bedroom, which is what this app talks to. */
    val pcItems: List<ItemStack> get() = Gen1Items.read(root["pcItems"].asTable())

    /** The PC's item table, made if the save has never had one. */
    fun ensurePcItems(): LuaValue.Table =
        root["pcItems"].asTable() ?: LuaValue.Table().also { root["pcItems"] = it }

    // ------- party

    private val partyTable: LuaValue.Table? get() = root["party"].asTable()

    val party: List<Gen1Pokemon>
        get() = partyTable?.array().orEmpty().mapNotNull { it.asTable()?.let(::Gen1Pokemon) }

    val partyCount: Int get() = party.size

    // ------- boxes

    /**
     * The PC as the game presents it after `Boxes.ensure` runs on load: always
     * 12 boxes, with a pre-12-box save's single `box` list showing as box 1.
     * Reading never mutates the save; see [ensureBoxes].
     */
    val boxes: List<List<Gen1Pokemon>>
        get() {
            val boxesTable = root["boxes"].asTable()
            if (boxesTable != null) {
                return (1..BOX_COUNT).map { index ->
                    boxesTable[index].asTable()?.array().orEmpty()
                        .mapNotNull { it.asTable()?.let(::Gen1Pokemon) }
                }
            }
            val legacy = root["box"].asTable()?.array().orEmpty()
                .mapNotNull { it.asTable()?.let(::Gen1Pokemon) }
            return (1..BOX_COUNT).map { if (it == 1) legacy else emptyList() }
        }

    val storedCount: Int get() = boxes.sumOf { it.size }

    val currentBox: Int get() = (root["currentBox"].asInt() ?: 1).coerceIn(1, BOX_COUNT)

    fun boxName(index: Int): String =
        root["boxNames"].asTable()?.get(index).asString()?.let(LuaText::displayText)
            ?: "BOX $index"

    /**
     * Upstream `Boxes.ensure`: materialise the 12 box arrays, migrate a
     * pre-12-box `box` list into box 1, and clamp `currentBox`. Called only
     * before a box is written, so a save this app merely reads is never
     * rewritten into a newer shape behind the player's back.
     */
    fun ensureBoxes(): LuaValue.Table {
        var boxesTable = root["boxes"].asTable()
        if (boxesTable == null) {
            boxesTable = LuaValue.Table()
            for (i in 1..BOX_COUNT) boxesTable[i] = LuaValue.Table()
            root["boxes"] = boxesTable
            root["currentBox"] = luaNum(1)
            val legacy = root["box"].asTable()
            if (legacy != null) {
                boxesTable[1] = LuaValue.Table.ofArray(legacy.array())
                root.remove(LuaKey.Name("box"))
            }
        }
        for (i in 1..BOX_COUNT) {
            if (boxesTable[i].asTable() == null) boxesTable[i] = LuaValue.Table()
        }
        root["currentBox"] = luaNum(currentBox)
        return boxesTable
    }

    // ------- mutation (transfer engine only; see the transfer package)

    /** Removes party slot [index] (1-based) with `table.remove` semantics. */
    fun removeFromParty(index: Int): LuaValue.Table? {
        val table = partyTable ?: return null
        val list = table.array().toMutableList()
        if (index !in 1..list.size) return null
        val removed = list.removeAt(index - 1)
        table.setArray(list)
        return removed.asTable()
    }

    /** Appends to the party, refusing past the Generation I limit of six. */
    fun addToParty(mon: LuaValue.Table): Boolean {
        val table = partyTable ?: LuaValue.Table().also { root["party"] = it }
        val list = table.array().toMutableList()
        if (list.size >= PARTY_MAX) return false
        // add_mon.asm _MoveMon's tail, as upstream BoxMenu.withdraw does: a
        // Pokémon with no stat block gets one before it joins a party.
        Gen1Stats.ensureStats(mon)
        list.add(mon)
        table.setArray(list)
        return true
    }

    fun removeFromBox(boxIndex: Int, index: Int): LuaValue.Table? {
        val boxesTable = ensureBoxes()
        val box = boxesTable[boxIndex].asTable() ?: return null
        val list = box.array().toMutableList()
        if (index !in 1..list.size) return null
        val removed = list.removeAt(index - 1)
        box.setArray(list)
        return removed.asTable()
    }

    fun addToBox(boxIndex: Int, mon: LuaValue.Table): Boolean {
        val boxesTable = ensureBoxes()
        val box = boxesTable[boxIndex].asTable() ?: return false
        val list = box.array().toMutableList()
        if (list.size >= BOX_CAPACITY) return false
        list.add(mon)
        box.setArray(list)
        return true
    }

    fun boxFreeSlots(boxIndex: Int): Int =
        BOX_CAPACITY - (boxes.getOrNull(boxIndex - 1)?.size ?: BOX_CAPACITY)

    fun deepCopy(): Gen1RecompSave = Gen1RecompSave(root.deepCopy())

    companion object {
        /** `src/pokemon/Party.lua` PARTY_LENGTH. */
        const val PARTY_MAX = 6

        /** `src/pokemon/Boxes.lua` COUNT and CAPACITY — Bill's PC, 12 x 20. */
        const val BOX_COUNT = 12
        const val BOX_CAPACITY = 20

        /** Gym order from `src/inventory/Badges.lua`. */
        val BADGE_IDS = listOf(
            "BOULDERBADGE", "CASCADEBADGE", "THUNDERBADGE", "RAINBOWBADGE",
            "SOULBADGE", "MARSHBADGE", "VOLCANOBADGE", "EARTHBADGE",
        )
    }
}
