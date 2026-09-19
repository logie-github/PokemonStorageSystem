package com.logie.gen1storage.storage

import com.logie.gen1storage.lua.LuaKey
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.asTable
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import com.logie.gen1storage.pokemon.Gen1Pokemon
import java.security.MessageDigest
import java.util.UUID

/**
 * The mark this app puts on a Pokémon, and every cartridge it has been in.
 *
 * The app's old answer to "is this the one I am holding?" was the Pokémon's
 * content fingerprint, which is the SHA-256 of its whole table. That is exact
 * and it is also fragile in the one way that matters: a Pokémon that gains a
 * level, learns a move, is healed at a centre or is given a nickname is a
 * different table and so, to the app, a different Pokémon. Which is fine for
 * proving a transfer removed what it read — the two reads are seconds apart —
 * and useless for "this thing in a Gold save is the one that left my PC in
 * March".
 *
 * So the app marks them. [tag] is written into the Pokémon's own table before
 * it goes into a cartridge, where it rides along with everything else the save
 * carries and comes back on the way in. Nothing in the games reads the key and
 * nothing draws it, which is the point: it is the app's handwriting on the
 * back of the photograph.
 *
 * [hops] is where that Pokémon has been, in order. A Pokémon that left for
 * Red, came back, went out to Crystal and came back again has four entries and
 * they say so, which is the whole of "track their history".
 */
data class Lineage(
    val tag: String,
    val hops: List<Hop> = emptyList(),
) {

    /** The same lineage with one more stop on it, oldest trimmed off first. */
    fun then(hop: Hop): Lineage = Lineage(tag, (hops + hop).takeLast(MAX_HOPS))

    /** The last cartridge this one was handed to, if it has been out at all. */
    val lastOut: Hop? get() = hops.lastOrNull { it.kind == Hop.Kind.WITHDRAWN }

    /** Whether the app believes it is in a cartridge right now. */
    val isOut: Boolean get() = hops.lastOrNull()?.kind == Hop.Kind.WITHDRAWN

    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["tag"] = luaStr(tag)
        this["hops"] = LuaValue.Table.ofArray(hops.map { it.toLua() })
    }

    companion object {
        /**
         * The key the tag is written under inside the Pokémon's own table.
         *
         * Deliberately not a name any cartridge field has, and deliberately
         * not read by anything that draws a Pokémon: a save that comes back
         * without it has been through something that dropped unknown keys,
         * and the app falls back to [identityOf] rather than to guessing.
         */
        const val TAG_KEY = "pssTag"

        /** How far back a Pokémon's history is kept. Long enough for a life. */
        const val MAX_HOPS = 64

        fun newTag(): String = UUID.randomUUID().toString().replace("-", "").take(16)

        fun fromLua(table: LuaValue.Table): Lineage? {
            val tag = table["tag"].asString() ?: return null
            val hops = table["hops"].asTable()?.array().orEmpty()
                .mapNotNull { it.asTable()?.let(Hop::fromLua) }
            return Lineage(tag, hops)
        }

        /** The tag written on this table, if the app has ever marked it. */
        fun tagOf(table: LuaValue.Table): String? =
            table[LuaKey.Name(TAG_KEY)].asString()?.takeIf { it.isNotBlank() }

        /** The same table with the tag on it, ready to go into a cartridge. */
        fun stamp(table: LuaValue.Table, tag: String): LuaValue.Table = table.apply {
            this[LuaKey.Name(TAG_KEY)] = luaStr(tag)
        }

        /**
         * The same table with the tag taken off again.
         *
         * The mark belongs in cartridges, not in the PC. What the boxes hold
         * is exactly the fields the cartridge handed over — the app's own
         * bookkeeping has always lived beside the Pokémon rather than inside
         * it, and the tag is bookkeeping. It is put on at the moment of
         * leaving and lifted off at the moment of arriving, so a Pokémon that
         * has been round a dozen cartridges still has one tag on it rather
         * than a dozen, and a Pokémon that never leaves is untouched.
         */
        fun unstamp(table: LuaValue.Table): LuaValue.Table = table.apply {
            remove(LuaKey.Name(TAG_KEY))
        }

        /**
         * What a Pokémon still is after a season of play.
         *
         * The fallback for one that comes back without its tag, built only
         * from the three things a Pokémon cannot change about itself: the
         * trainer who caught it, that trainer's id, and its DVs. Levels,
         * moves, HP, status, nickname and even its species all move; these do
         * not. It is weaker than the tag — two Pokémon caught by the same
         * trainer with the same sixteen bits of DVs read alike — so it is only
         * ever consulted when there is no tag to consult.
         */
        fun identityOf(pokemon: Gen1Pokemon): String? {
            val otId = pokemon.otId ?: return null
            val ot = pokemon.otName ?: return null
            val dvs = pokemon.dvs.entries
                .sortedBy { it.key.name }
                .joinToString(",") { "${it.key.name}=${it.value}" }
            if (dvs.isEmpty()) return null
            val bytes = "$otId|$ot|$dvs".toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .take(16)
        }
    }
}

/** One stop on a Pokémon's way around the cartridges. */
data class Hop(
    val kind: Kind,
    val atMillis: Long,
    /** The cartridge this stop is about, where there is one. */
    val gameVersion: String? = null,
    val saveKey: String? = null,
    val trainerName: String? = null,
) {
    enum class Kind {
        /** Came out of a cartridge and into the PC. */
        DEPOSITED,

        /** Left the PC for a cartridge. */
        WITHDRAWN,

        /** Went forward through the Time Capsule, which is not a cartridge. */
        CARRIED,

        /**
         * Found in a cartridge that the PC also thought it was holding.
         *
         * Which means it left by some route this app never saw — a save
         * restored from a backup, a cartridge copied, the game's own trade.
         */
        SEEN_ELSEWHERE,
    }

    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["kind"] = luaStr(kind.name)
        this["at"] = luaNum(atMillis.toDouble())
        gameVersion?.let { this["game"] = luaStr(it) }
        saveKey?.let { this["save"] = luaStr(it) }
        trainerName?.let { this["trainer"] = luaStr(it) }
    }

    companion object {
        fun fromLua(table: LuaValue.Table): Hop? {
            val kind = table["kind"].asString()
                ?.let { name -> Kind.entries.firstOrNull { it.name == name } }
                ?: return null
            return Hop(
                kind = kind,
                atMillis = table["at"].asInt()?.toLong()
                    ?: (table["at"] as? LuaValue.Num)?.value?.toLong() ?: 0L,
                gameVersion = table["game"].asString(),
                saveKey = table["save"].asString(),
                trainerName = table["trainer"].asString(),
            )
        }
    }
}
