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
 * (arrays of up to 20, twelve of them as the game ships) with pre-12-box saves
 * carrying a single `box` list, `currentBox` selects the active box, and
 * `player` holds the trainer.
 *
 * How many boxes there are is read off each save rather than assumed: mods add
 * them, and a save that has more than twelve is not a broken save.
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

    // ------- mods

    /**
     * A mod that has said who it is in the save.
     *
     * Gen1Recomp gives a mod no way to introduce itself to anything outside
     * the game, so the introduction goes where both sides can reach it: the
     * save's own `meta.mods`. A mod that writes a table there naming itself
     * and what it changed is one this app can work with knowingly rather than
     * by inference.
     *
     * Nothing here is trusted over the save itself. What a mod says about the
     * boxes is a description of what it did; how many boxes there actually are
     * is still counted off the file. A declaration that disagrees with the
     * data loses.
     */
    data class SaveMod(
        val id: String,
        val name: String,
        val version: String?,
        /** How many boxes the mod says it makes, if it says. */
        val boxCount: Int?,
        /** How many fit in one, if it says. */
        val boxCapacity: Int?,
    )

    /**
     * Every mod the save names, however it names them.
     *
     * `meta.mods` is read three ways because a mod list can reasonably be
     * written three ways — a list of ids, a set of id-to-true, or a map of id
     * to a table describing it — and refusing the two shapes that carry less
     * information would mean reporting nothing rather than reporting a name.
     */
    val mods: List<SaveMod>
        get() {
            val table = root["meta"].asTable()?.get("mods").asTable() ?: return emptyList()
            return table.entries().mapNotNull { (key, value) ->
                val id = when (key) {
                    is LuaKey.Name -> key.value
                    // A plain list of ids: the key is the position, the value
                    // is the name.
                    is LuaKey.Index -> value.asString()
                    else -> null
                } ?: return@mapNotNull null
                val described = value.asTable()
                if (described == null && value.asBoolean() == false) return@mapNotNull null
                SaveMod(
                    id = id,
                    name = described?.get("name").asString() ?: id,
                    version = described?.get("version").asString(),
                    boxCount = described?.get("boxCount").asInt(),
                    boxCapacity = described?.get("boxCapacity").asInt(),
                )
            }
        }

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
     * How many boxes this save actually has.
     *
     * The game ships with twelve and a mod can add more, so this counts what
     * the file carries rather than taking the twelve on faith: the boxes
     * running from one upwards, and never fewer than the twelve
     * `Boxes.ensure` would make on load.
     *
     * Counted from one rather than by the highest number present, because
     * that is how a Lua array is shaped and how the game walks it. A stray
     * high-numbered key past a gap is left alone rather than treated as a
     * box — and left alone means left in the file untouched.
     */
    val boxCount: Int
        get() {
            val boxesTable = root["boxes"].asTable() ?: return BOX_COUNT
            var found = 0
            while (boxesTable[found + 1].asTable() != null) found++
            return maxOf(found, BOX_COUNT)
        }

    /**
     * The PC as the game presents it after `Boxes.ensure` runs on load: every
     * box the save has, with a pre-12-box save's single `box` list showing as
     * box 1. Reading never mutates the save; see [ensureBoxes].
     */
    val boxes: List<List<Gen1Pokemon>>
        get() {
            val boxesTable = root["boxes"].asTable()
            if (boxesTable != null) {
                return (1..boxCount).map { index ->
                    boxesTable[index].asTable()?.array().orEmpty()
                        .mapNotNull { it.asTable()?.let(::Gen1Pokemon) }
                }
            }
            val legacy = root["box"].asTable()?.array().orEmpty()
                .mapNotNull { it.asTable()?.let(::Gen1Pokemon) }
            return (1..BOX_COUNT).map { if (it == 1) legacy else emptyList() }
        }

    val storedCount: Int get() = boxes.sumOf { it.size }

    /**
     * Which box the game has open, clamped to the boxes this save has.
     *
     * Clamping to twelve was the dangerous half of assuming twelve: a modded
     * player sitting on box twenty would have had it moved to twelve the
     * first time this app wrote the save, because [ensureBoxes] writes this
     * value back.
     */
    val currentBox: Int get() = (root["currentBox"].asInt() ?: 1).coerceIn(1, boxCount)

    fun boxName(index: Int): String =
        root["boxNames"].asTable()?.get(index).asString()?.let(LuaText::displayText)
            ?: "BOX $index"

    /**
     * Upstream `Boxes.ensure`: materialise the box arrays, migrate a
     * pre-12-box `box` list into box 1, and clamp `currentBox`. Called only
     * before a box is written, so a save this app merely reads is never
     * rewritten into a newer shape behind the player's back.
     *
     * A save with more boxes than the game ships with keeps every one of
     * them: this fills in what is missing up to what the save already has and
     * never goes past it, so nothing is invented and nothing is dropped.
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
        for (i in 1..boxCount) {
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
        if (list.size >= boxCapacity) return false
        list.add(mon)
        box.setArray(list)
        return true
    }

    /**
     * How many fit in one box.
     *
     * Twenty as the game ships, or whatever a mod has said it made them: a
     * mod that doubles a box and says so is one this app can fill to the top
     * rather than stopping at twenty and calling the rest full. Where two
     * mods disagree the larger wins, which is the one that can actually hold
     * what is already there.
     */
    val boxCapacity: Int
        get() = mods.mapNotNull { it.boxCapacity }.filter { it > 0 }.maxOrNull() ?: BOX_CAPACITY

    fun boxFreeSlots(boxIndex: Int): Int =
        boxCapacity - (boxes.getOrNull(boxIndex - 1)?.size ?: boxCapacity)

    fun deepCopy(): Gen1RecompSave = Gen1RecompSave(root.deepCopy())

    companion object {
        /** `src/pokemon/Party.lua` PARTY_LENGTH. */
        const val PARTY_MAX = 6

        /**
         * `src/pokemon/Boxes.lua` COUNT and CAPACITY — Bill's PC, 12 x 20.
         *
         * [BOX_COUNT] is what the game ships with and what a save is given if
         * it has none; it is a floor, not a limit. What a particular save has
         * is [boxCount].
         */
        const val BOX_COUNT = 12
        const val BOX_CAPACITY = 20

        /** Gym order from `src/inventory/Badges.lua`. */
        val BADGE_IDS = listOf(
            "BOULDERBADGE", "CASCADEBADGE", "THUNDERBADGE", "RAINBOWBADGE",
            "SOULBADGE", "MARSHBADGE", "VOLCANOBADGE", "EARTHBADGE",
        )
    }
}
