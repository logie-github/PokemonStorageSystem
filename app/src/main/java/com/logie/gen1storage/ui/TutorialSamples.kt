package com.logie.gen1storage.ui

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.gen1recomp.Gen1RecompSave
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Data
import com.logie.gen1storage.storage.Provenance
import com.logie.gen1storage.storage.StorageBox
import com.logie.gen1storage.storage.StorageLayout
import com.logie.gen1storage.storage.StoredPokemon
import com.logie.gen1storage.sync.RemoteSave
import com.logie.gen1storage.sync.SaveSummary

/**
 * The playthrough the introduction is shown against.
 *
 * Every screen Bill walks through is the real screen, so it needs real things
 * to draw: a save, a party, a boxful. None of it is ever written anywhere —
 * it is built here, handed to the tutorial's copies of the screens, and
 * dropped when the introduction ends. A player's own PC is untouched by it,
 * which is why the introduction can be replayed from OPTIONS at any point
 * rather than only while the app is empty.
 *
 * The levels are a mid-game Kanto team rather than a row of identical ones:
 * the point of showing a screen is that it looks like a screen someone would
 * actually have.
 */
object TutorialSamples {

    const val TRAINER = "RED"
    private const val TRAINER_ID = 27671

    /** Nothing here is ever stored, so one fixed key is enough to identify it. */
    const val SAVE_KEY = "tutorial/sample"

    /**
     * A party as one looks partway through a playthrough: a starter that has
     * been carried the whole way, and the four picked up around it.
     */
    private val PARTY = listOf(
        "CHARMELEON" to 32,
        "PIKACHU" to 28,
        "BUTTERFREE" to 24,
        "KADABRA" to 27,
        "PIDGEOTTO" to 25,
    )

    /** What a box actually looks like: a handful, not a full grid. */
    private val BOXED = listOf(
        "NIDORINO" to 22,
        "GEODUDE" to 19,
        "ODDISH" to 14,
        "MAGIKARP" to 9,
        "GROWLITHE" to 26,
        "ABRA" to 12,
        "DIGLETT" to 17,
        "PSYDUCK" to 21,
        "MACHOP" to 20,
        "ZUBAT" to 11,
        "VOLTORB" to 23,
        "TENTACOOL" to 18,
    )

    /**
     * Every species the introduction draws, in the order it draws them.
     *
     * This is the list the first launch fetches before it starts on the other
     * two hundred and fifty: a dozen files land in a couple of seconds, and
     * by the time Bill has finished his opening line the screens he is about
     * to show have their art. See [StorageViewModel.downloadFirstRun].
     */
    val SPECIES: List<String> = (PARTY + BOXED).map { it.first }.distinct()

    /**
     * The same list as the follower sheets are numbered, for the box grid —
     * which is drawn out of dex numbers rather than names.
     */
    val DEX_NUMBERS: List<Int> = SPECIES.mapNotNull { Gen1Data.species(it)?.dexNumber }

    private fun party(): List<LuaValue.Table> = PARTY.map { (species, level) -> mon(species, level) }

    private fun boxed(): List<LuaValue.Table> = BOXED.map { (species, level) -> mon(species, level) }

    /**
     * One Pokémon, with only the fields the screens actually read.
     *
     * Deliberately not a full cartridge record: stats, DVs and moves are
     * derived or unused on the card, the box grid and the transfer scene, and
     * a sample that pretends to be a complete save is a sample someone will
     * one day be tempted to deposit.
     */
    private fun mon(species: String, level: Int, nickname: String? = null): LuaValue.Table =
        LuaValue.Table().apply {
            this["species"] = luaStr(species)
            this["level"] = luaNum(level)
            nickname?.let { this["nickname"] = luaStr(it) }
            this["ot"] = luaStr(TRAINER)
            this["otId"] = luaNum(TRAINER_ID)
        }

    /** The card's save: name, money, time, badges, and the party behind it. */
    fun save(): Gen1RecompSave {
        val root = LuaValue.Table()
        root["version"] = luaStr(GameVersion.RED.id)
        root["meta"] = LuaValue.Table().apply {
            this["format"] = luaNum(5)
            this["playthroughId"] = luaStr("tutorial")
        }
        root["player"] = LuaValue.Table().apply {
            this["name"] = luaStr(TRAINER)
            this["id"] = luaNum(TRAINER_ID)
        }
        root["party"] = LuaValue.Table.ofArray(party())
        root["inventory"] = LuaValue.Table().apply {
            listOf("BOULDERBADGE", "CASCADEBADGE", "THUNDERBADGE", "RAINBOWBADGE")
                .forEach { this[it] = luaNum(1) }
        }
        root["money"] = luaNum(21430)
        // Eleven hours and a bit, which is about where four badges sit.
        root["playTime"] = luaNum(41_520.0)
        return Gen1RecompSave(root)
    }

    /** The same playthrough as the account lists it. */
    fun remote(): RemoteSave = RemoteSave(
        key = SAVE_KEY,
        version = GameVersion.RED,
        playthroughId = "tutorial",
        rev = 1,
        slot = null,
        summary = SaveSummary(
            trainerName = TRAINER,
            badges = 4,
            timeText = "11:32",
            dexCount = 41,
            savedAtEpochSeconds = null,
            playTimeSeconds = 41_520.0,
            format = 5,
        ),
    )

    /** The box as the grid wants it: every spot, null where nothing sits. */
    fun box(): StorageBox {
        val stored = boxed().mapIndexed { index, data ->
            StoredPokemon(
                uid = "tutorial-$index",
                data = data,
                provenance = provenance(index),
                generation = 1,
            )
        }
        val slots = List(StorageLayout.BOX_CAPACITY) { stored.getOrNull(it) }
        return StorageBox(StorageLayout.THE_BOX, name = null, slots = slots)
    }

    /** The one the transfer scene carries, and the one Bill names. */
    fun travelling(): StoredPokemon = StoredPokemon(
        uid = "tutorial-travelling",
        data = mon("PIKACHU", 28),
        provenance = provenance(0),
        generation = 1,
    )

    private fun provenance(index: Int) = Provenance(
        gameVersion = GameVersion.RED.id,
        saveId = SAVE_KEY,
        savePath = SAVE_KEY,
        slotId = null,
        trainerName = TRAINER,
        trainerId = TRAINER_ID,
        playthroughId = "tutorial",
        sourceKind = Provenance.KIND_PARTY,
        sourceIndex = index,
        depositedAtEpochMillis = 0L,
    )
}
