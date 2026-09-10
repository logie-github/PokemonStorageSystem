package com.logie.gen1storage.transfer

import com.logie.gen1storage.lua.LuaParser
import com.logie.gen1storage.lua.LuaText
import com.logie.gen1storage.lua.LuaValue
import com.logie.gen1storage.lua.LuaWriter
import com.logie.gen1storage.lua.asInt
import com.logie.gen1storage.lua.asString
import com.logie.gen1storage.lua.luaNum
import com.logie.gen1storage.lua.luaStr
import java.io.File
import java.io.RandomAccessFile

enum class TransferKind { DEPOSIT, WITHDRAW }

/**
 * How far a transfer had got. The stage alone never decides recovery — the
 * save's own hash does — but it says which side of the move to look at.
 */
enum class TransferStage { PREPARED, STORED, SAVED }

/**
 * The record of a transfer that is in flight.
 *
 * The two hashes are what makes crash recovery a decision instead of a guess:
 * [saveHashBefore] is the save exactly as it was read, [saveHashAfter] is the
 * save exactly as it will be once committed. On the next launch the save's
 * current hash matches one of them, and that says whether the write landed.
 * Matching neither means the game wrote the save in between, and the app stops
 * and says so rather than choosing for the player.
 */
data class TransferEntry(
    val id: String,
    val kind: TransferKind,
    val stage: TransferStage,
    val uid: String,
    val monFingerprint: String,
    val saveId: String,
    val savePath: String,
    val saveDirectoryKey: String,
    val mainName: String,
    val saveHashBefore: String,
    val saveHashAfter: String,
    val sourceKind: String,
    val sourceIndex: Int,
    val boxIndex: Int,
    val startedAtEpochMillis: Long,
    val note: String? = null,
) {
    fun toLua(): LuaValue.Table = LuaValue.Table().apply {
        this["id"] = luaStr(id)
        this["kind"] = luaStr(kind.name)
        this["stage"] = luaStr(stage.name)
        this["uid"] = luaStr(uid)
        this["monFingerprint"] = luaStr(monFingerprint)
        this["saveId"] = luaStr(saveId)
        this["savePath"] = luaStr(savePath)
        this["saveDirectoryKey"] = luaStr(saveDirectoryKey)
        this["mainName"] = luaStr(mainName)
        this["saveHashBefore"] = luaStr(saveHashBefore)
        this["saveHashAfter"] = luaStr(saveHashAfter)
        this["sourceKind"] = luaStr(sourceKind)
        this["sourceIndex"] = luaNum(sourceIndex)
        this["boxIndex"] = luaNum(boxIndex)
        this["startedAt"] = luaNum(startedAtEpochMillis.toDouble())
        note?.let { this["note"] = luaStr(it) }
    }

    companion object {
        fun fromLua(table: LuaValue.Table): TransferEntry? {
            val id = table["id"].asString() ?: return null
            val kind = table["kind"].asString()?.let { runCatching { TransferKind.valueOf(it) }.getOrNull() }
                ?: return null
            val stage = table["stage"].asString()?.let { runCatching { TransferStage.valueOf(it) }.getOrNull() }
                ?: return null
            return TransferEntry(
                id = id,
                kind = kind,
                stage = stage,
                uid = table["uid"].asString().orEmpty(),
                monFingerprint = table["monFingerprint"].asString().orEmpty(),
                saveId = table["saveId"].asString().orEmpty(),
                savePath = table["savePath"].asString().orEmpty(),
                saveDirectoryKey = table["saveDirectoryKey"].asString().orEmpty(),
                mainName = table["mainName"].asString().orEmpty(),
                saveHashBefore = table["saveHashBefore"].asString().orEmpty(),
                saveHashAfter = table["saveHashAfter"].asString().orEmpty(),
                sourceKind = table["sourceKind"].asString().orEmpty(),
                sourceIndex = table["sourceIndex"].asInt() ?: 0,
                boxIndex = table["boxIndex"].asInt() ?: 1,
                startedAtEpochMillis = (table["startedAt"] as? LuaValue.Num)?.value?.toLong() ?: 0L,
                note = table["note"].asString(),
            )
        }
    }
}

/**
 * The on-disk journal. At most one transfer is ever in flight, so this is a
 * single small file written before the first destructive step and cleared
 * after the last one.
 */
class TransferJournal(private val directory: File) {

    private val file = File(directory, FILE_NAME)

    fun read(): TransferEntry? {
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            LuaParser.parse(LuaText.decode(file.readBytes()))["entry"]
                ?.let { it as? LuaValue.Table }
                ?.let(TransferEntry::fromLua)
        }.getOrNull()
    }

    fun write(entry: TransferEntry) {
        directory.mkdirs()
        val root = LuaValue.Table().apply { this["entry"] = entry.toLua() }
        val bytes = LuaText.encode(LuaWriter.encode(root))
        // Written synchronously: the journal has to be durable before the step
        // it describes, or it cannot describe it.
        RandomAccessFile(file, "rw").use { sink ->
            sink.setLength(0)
            sink.write(bytes)
            sink.fd.sync()
        }
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "transfer-journal.lua"
    }
}
