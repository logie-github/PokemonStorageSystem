package com.logie.gen1storage

import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr

/**
 * Save fixtures built the way Gen1Recomp builds them: the same tables, the same
 * field names, serialized through the same grammar.
 *
 * Every fixture is produced fresh by a function, so a test can never mutate the
 * fixture another test depends on.
 */
object SaveFixtures {

    fun pokemon(
        species: String = "PIKACHU",
        level: Int = 25,
        nickname: String? = null,
        ot: String = "ASH",
        otId: Int = 12345,
        hp: Int? = null,
        exp: Int = 15625,
        status: String? = null,
        dvs: List<Int> = listOf(15, 12, 10, 9),
        statExp: List<Int> = listOf(0, 0, 0, 0, 0),
        moves: List<Triple<String, Int, Int?>> = listOf(
            Triple("THUNDERBOLT", 15, null),
            Triple("QUICK_ATTACK", 30, null),
            Triple("THUNDER_WAVE", 20, null),
            Triple("GROWL", 40, null),
        ),
        withStats: Boolean = true,
        catchRate: Int? = 190,
        extraFields: Map<String, LuaValue> = emptyMap(),
    ): LuaValue.Table {
        val (attack, defense, speed, special) = dvs
        val hpDv = (attack % 2) * 8 + (defense % 2) * 4 + (speed % 2) * 2 + (special % 2)
        val mon = LuaValue.Table()
        mon["species"] = luaStr(species)
        mon["level"] = luaNum(level)
        mon["exp"] = luaNum(exp)
        nickname?.let { mon["nickname"] = luaStr(it) }
        mon["ot"] = luaStr(ot)
        mon["otId"] = luaNum(otId)
        status?.let { mon["status"] = luaStr(it) }
        catchRate?.let { mon["catchRate"] = luaNum(it) }

        mon["dvs"] = LuaValue.Table().apply {
            this["hp"] = luaNum(hpDv)
            this["attack"] = luaNum(attack)
            this["defense"] = luaNum(defense)
            this["speed"] = luaNum(speed)
            this["special"] = luaNum(special)
        }
        mon["statExp"] = LuaValue.Table().apply {
            listOf("hp", "attack", "defense", "speed", "special").forEachIndexed { index, key ->
                this[key] = luaNum(statExp.getOrElse(index) { 0 })
            }
        }
        if (withStats) {
            val species1 = com.logie.gen1storage.pokemon.Gen1Data.species(species)
            val computed = species1?.let {
                com.logie.gen1storage.pokemon.Gen1Stats.calc(
                    it,
                    level,
                    com.logie.gen1storage.pokemon.Gen1Stat.ORDER.zip(
                        listOf(hpDv, attack, defense, speed, special)
                    ).toMap(),
                    com.logie.gen1storage.pokemon.Gen1Stat.ORDER.zip(
                        List(5) { statExp.getOrElse(it) { 0 } }
                    ).toMap(),
                )
            }
            if (computed != null) {
                mon["stats"] = LuaValue.Table().apply {
                    computed.forEach { (stat, value) -> this[stat.key] = luaNum(value) }
                }
                mon["hp"] = luaNum(hp ?: computed.getValue(com.logie.gen1storage.pokemon.Gen1Stat.HP))
            }
        } else {
            mon["hp"] = luaNum(hp ?: 20)
        }

        mon["moves"] = LuaValue.Table.ofArray(
            moves.map { (id, pp, ppUps) ->
                LuaValue.Table().apply {
                    this["id"] = luaStr(id)
                    this["pp"] = luaNum(pp)
                    ppUps?.let { this["ppUps"] = luaNum(it) }
                }
            }
        )
        extraFields.forEach { (key, value) -> mon[key] = value }
        return mon
    }

    fun save(
        version: String = "red",
        trainer: String = "ASH",
        trainerId: Int = 12345,
        party: List<LuaValue.Table> = listOf(pokemon()),
        boxes: List<List<LuaValue.Table>>? = null,
        legacyBox: List<LuaValue.Table>? = null,
        currentBox: Int = 1,
        badges: List<String> = listOf("BOULDERBADGE", "CASCADEBADGE"),
        playTime: Double = 7265.5,
        playthroughId: String? = "quiet-forest-dawn",
    ): LuaValue.Table {
        val root = LuaValue.Table()
        root["version"] = luaStr(version)
        root["meta"] = LuaValue.Table().apply {
            this["format"] = luaNum(5)
            playthroughId?.let { this["playthroughId"] = luaStr(it) }
            this["mods"] = LuaValue.Table()
        }
        root["player"] = LuaValue.Table().apply {
            this["name"] = luaStr(trainer)
            this["rival"] = luaStr("BLUE")
            this["id"] = luaNum(trainerId)
            this["map"] = luaStr("PALLET_TOWN")
            this["x"] = luaNum(5)
            this["y"] = luaNum(6)
            this["facing"] = luaStr("down")
        }
        root["party"] = LuaValue.Table.ofArray(party)
        if (legacyBox != null) {
            root["box"] = LuaValue.Table.ofArray(legacyBox)
        }
        if (boxes != null) {
            root["boxes"] = LuaValue.Table().apply {
                (1..12).forEach { index ->
                    this[index] = LuaValue.Table.ofArray(boxes.getOrElse(index - 1) { emptyList() })
                }
            }
            root["currentBox"] = luaNum(currentBox)
        }
        root["inventory"] = LuaValue.Table().apply {
            badges.forEach { this[it] = luaNum(1) }
            this["POTION"] = luaNum(3)
        }
        root["pcItems"] = LuaValue.Table().apply { this["POTION"] = luaNum(1) }
        root["pokedex"] = LuaValue.Table().apply {
            this["owned"] = LuaValue.Table().apply {
                this["PIKACHU"] = LuaValue.Bool(true)
                this["BULBASAUR"] = LuaValue.Bool(true)
            }
            this["seen"] = LuaValue.Table().apply { this["PIKACHU"] = LuaValue.Bool(true) }
        }
        root["flags"] = LuaValue.Table()
        root["defeatedTrainers"] = LuaValue.Table()
        root["money"] = luaNum(3000)
        root["playTime"] = luaNum(playTime)
        root["repelSteps"] = luaNum(0)
        root["modData"] = LuaValue.Table()
        root["lastHeal"] = LuaValue.Table().apply {
            this["map"] = luaStr("PALLET_TOWN")
            this["x"] = luaNum(5)
            this["y"] = luaNum(6)
        }
        return root
    }

    fun encode(root: LuaValue.Table): String = LuaWriter.encode(root)

    /** A full box of twenty distinct Pokémon. */
    fun fullBox(): List<LuaValue.Table> = (1..20).map { index ->
        pokemon(
            species = "RATTATA",
            level = index,
            nickname = if (index % 2 == 0) "RAT$index" else null,
            otId = 1000 + index,
        )
    }
}

private operator fun <T> List<T>.component4(): T = this[3]
