package com.logie.gen1storage.sync

import com.logie.gen1storage.gen1recomp.GameVersion
import com.logie.gen1storage.transfer.TransferNote
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** The outcome of one sync call. An HTTP status is data here, never an exception. */
sealed interface SyncResult<out T> {
    data class Ok<T>(val value: T) : SyncResult<T>

    /**
     * The server refused a write because the save moved on since it was read.
     * Never resolved by forcing: the point of `baseRev` is that the other side
     * has something this app has not seen.
     */
    data class Conflict(val remoteRev: Long?, val remote: SaveSummary?) : SyncResult<Nothing>

    /** The device is not linked, or its token was revoked from another device. */
    data object Unauthorized : SyncResult<Nothing>

    data class Failed(val message: String, val code: Int? = null) : SyncResult<Nothing>
}

inline fun <T> SyncResult<T>.getOrNull(): T? = (this as? SyncResult.Ok<T>)?.value

/** What the server knows about a save without sending the save itself. */
data class SaveSummary(
    val trainerName: String?,
    val badges: Int?,
    val timeText: String?,
    val dexCount: Int?,
    val savedAtEpochSeconds: Long?,
    val playTimeSeconds: Double?,
    val format: Int?,
)

/** One playthrough on the account, as `GET /sync/state` lists it. */
data class RemoteSave(
    val key: String,
    val version: GameVersion,
    val playthroughId: String,
    val rev: Long,
    val slot: String?,
    val summary: SaveSummary,
) {
    val label: String get() = summary.trainerName ?: playthroughId
}

/**
 * A save's actual bytes, with the revision they were read at.
 *
 * [slot] is whatever the server happens to say about which of the game's save
 * slots this playthrough sits in. The account listing does not carry it, so
 * this is the other place worth looking: it costs nothing to read, and where
 * it is there the app can open the game straight at this save.
 */
data class SaveBlob(
    val blob: String,
    val rev: Long,
    val summary: SaveSummary?,
    val slot: String? = null,
)

data class LinkedDevice(val id: String, val label: String, val isThisDevice: Boolean)

data class AccountState(
    val saves: List<RemoteSave>,
    val devices: List<LinkedDevice>,
    /** Keys the server has a tombstone for; the game deleted these. */
    val deleted: Set<String>,
    val unsupported: List<String>,
)

data class LinkResult(val account: String, val deviceToken: String, val deviceId: String?)

/**
 * The Gen1Recomp save-sync API, ported from upstream `src/sync/SyncClient.lua`.
 *
 * Endpoints, field names and the auth headers are taken from that file; the
 * blob a save is carried in is the save file's own Lua source verbatim (see
 * `SyncEngine.defaultSaves`, where `blob = source` from
 * `SaveData.readSlotSource`), which is exactly the text this app's parser and
 * writer already round-trip byte for byte.
 *
 * A save's identity on the server is `<version>/<meta.playthroughId>`, and
 * every write carries the `baseRev` it was read at so the server rejects a
 * stale overwrite with 409.
 */
class SyncApi(
    private val transport: SyncTransport = UrlSyncTransport(),
    private val baseUrl: String = DEFAULT_URL,
    private val credentials: () -> Pair<String, String>? = { null },
) {

    suspend fun link(code1: String, code2: String, deviceLabel: String): SyncResult<LinkResult> {
        val a = normalizeCode(code1) ?: return SyncResult.Failed("Both codes are 8 digits")
        val b = normalizeCode(code2) ?: return SyncResult.Failed("Both codes are 8 digits")
        val body = JSONObject()
            .put("code1", a)
            .put("code2", b)
            .put("device", deviceLabel)
        return request("POST", "/sync/link", body, authenticated = false) { json ->
            val account = json.optString("account").takeIf { it.isNotEmpty() }
            val token = json.optString("deviceToken").takeIf { it.isNotEmpty() }
            if (account == null || token == null) {
                throw JSONException("the server sent an unexpected reply")
            }
            LinkResult(account, token, json.optString("device").takeIf { it.isNotEmpty() })
        }
    }

    suspend fun state(): SyncResult<AccountState> =
        request("GET", "/sync/state", null) { json -> parseState(json) }

    suspend fun getSave(version: GameVersion, playthroughId: String): SyncResult<SaveBlob> {
        val path = "/sync/save?version=${encode(version.id)}&id=${encode(playthroughId)}"
        return request("GET", path, null) { json ->
            val blob = json.optString("blob")
            if (blob.isEmpty()) throw JSONException("the server sent no save data")
            SaveBlob(
                blob = blob,
                rev = json.optLong("rev", 0L),
                summary = json.optJSONObject("meta")?.let(::parseSummary),
                slot = slotOf(json),
            )
        }
    }

    /**
     * Uploads a save. [baseRev] is the revision the blob was read at; the
     * server answers 409 when it has moved on. `force` exists in the protocol
     * and is deliberately never sent — it would discard whatever the other
     * side wrote.
     */
    suspend fun putSave(
        version: GameVersion,
        playthroughId: String,
        slot: String?,
        blob: String,
        baseRev: Long?,
        summary: SaveSummary?,
    ): SyncResult<Long> {
        if (blob.toByteArray(Charsets.UTF_8).size > MAX_BLOB_BYTES) {
            return SyncResult.Failed("This save is too large to sync")
        }
        val body = JSONObject()
            .put("version", version.id)
            .put("blob", blob)
        slot?.let { body.put("slot", it) }
        baseRev?.let { body.put("baseRev", it) }
        body.put("meta", metaJson(playthroughId, summary))
        return request("PUT", "/sync/save", body) { json -> json.optLong("rev", 0L) }
    }

    // ------------------------------------------------------------------
    // Notes: what this app asks a cartridge to do to its own save.
    //
    // The save endpoints above are how this app used to change a cartridge —
    // read the whole file, edit it, write the whole file back — and two
    // programs doing that to one file is what forks a save. These three are
    // the other way round: this app leaves a note, the game applies it to its
    // own save on its own next sync, and answers. See [TransferNote].
    // ------------------------------------------------------------------

    /** Leaves a note for a cartridge. The server answers with it as stored. */
    suspend fun postNote(note: TransferNote): SyncResult<TransferNote> =
        request("POST", "/sync/notes", note.toJson()) { json ->
            json.optJSONObject("note")?.let(TransferNote::fromJson)
                ?: throw JSONException("the server did not echo the note back")
        }

    /**
     * Every note this account has outstanding, whatever state it is in.
     *
     * Read rather than remembered: what happened to a note is the game's to
     * say, and this app can be closed, reinstalled or replaced between
     * leaving one and hearing about it.
     */
    suspend fun notes(): SyncResult<List<TransferNote>> =
        request("GET", "/sync/notes", null) { json ->
            val rows = json.optJSONArray("notes") ?: return@request emptyList()
            (0 until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.let(TransferNote::fromJson)
            }
        }

    /** Clears a note this app has finished acting on. */
    suspend fun deleteNote(id: String): SyncResult<Unit> =
        request("POST", "/sync/notes/clear", JSONObject().put("id", id)) { }

    suspend fun reissueCodes(): SyncResult<Unit> =
        request("POST", "/sync/codes", JSONObject()) { }

    suspend fun unlink(deviceId: String?): SyncResult<Unit> {
        val body = JSONObject()
        deviceId?.let { body.put("device", it) }
        return request("POST", "/sync/unlink", body) { }
    }

    // ------------------------------------------------------------------

    private fun metaJson(playthroughId: String, summary: SaveSummary?): JSONObject {
        val meta = JSONObject().put("playthroughId", playthroughId)
        if (summary == null) return meta
        summary.savedAtEpochSeconds?.let { meta.put("savedAt", it) }
        summary.playTimeSeconds?.let { meta.put("playTime", it) }
        summary.format?.let { meta.put("format", it) }
        meta.put(
            "summary",
            JSONObject().apply {
                summary.trainerName?.let { put("name", it) }
                summary.badges?.let { put("badges", it) }
                summary.timeText?.let { put("timeText", it) }
                summary.dexCount?.let { put("dexCount", it) }
            },
        )
        return meta
    }

    private fun parseState(json: JSONObject): AccountState {
        val saves = mutableListOf<RemoteSave>()
        val unsupported = mutableListOf<String>()
        json.optJSONObject("saves")?.let { rows ->
            for (key in rows.keys()) {
                val row = rows.optJSONObject(key) ?: continue
                val versionId = key.substringBefore('/')
                val playthroughId = key.substringAfter('/', "")
                val version = GameVersion.fromId(versionId)
                if (version == null || playthroughId.isEmpty()) {
                    // A Gold/Silver/Crystal playthrough on the same account is
                    // listed but out of scope; naming it beats hiding it.
                    unsupported += key
                    continue
                }
                saves += RemoteSave(
                    key = key,
                    version = version,
                    playthroughId = playthroughId,
                    rev = row.optLong("rev", 0L),
                    slot = slotOf(row),
                    summary = parseSummary(row),
                )
            }
        }
        val devices = mutableListOf<LinkedDevice>()
        json.optJSONArray("devices")?.let { array ->
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optString("id").takeIf { it.isNotEmpty() } ?: continue
                devices += LinkedDevice(
                    id = id,
                    label = row.optString("label").takeIf { it.isNotEmpty() } ?: "device",
                    isThisDevice = row.optBoolean("current", false),
                )
            }
        }
        val deleted = json.optJSONObject("deleted")?.keys()?.asSequence()?.toSet().orEmpty()
        return AccountState(
            saves.sortedWith(compareBy({ it.version.ordinal }, { it.playthroughId })),
            devices,
            deleted,
            unsupported,
        )
    }

    /**
     * Which of that device's save slots this playthrough sits in.
     *
     * Upstream sends it beside the blob on `PUT /sync/save`, and it comes back
     * wherever the server chose to put it, so all three places are looked at
     * rather than only the top level. It is worth the trouble because it is
     * the one thing that says *which* save: the app hands it to the game's
     * launch link so opening the game lands on the playthrough that was just
     * written instead of on whichever one was last open.
     */
    private fun slotOf(row: JSONObject): String? {
        val places = listOf(row, row.optJSONObject("meta"), row.optJSONObject("remoteMeta"))
        return places.firstNotNullOfOrNull { where ->
            where?.optString("slot")?.takeIf { it.isNotEmpty() }
        }
    }

    /** Upstream `SyncEngine.metaOf`: inline, under `meta`, or under `remoteMeta`. */
    private fun parseSummary(row: JSONObject): SaveSummary {
        val meta = row.optJSONObject("meta") ?: row.optJSONObject("remoteMeta") ?: row
        val summary = meta.optJSONObject("summary")
        return SaveSummary(
            trainerName = summary?.optString("name")?.takeIf { it.isNotEmpty() },
            badges = summary?.takeIf { it.has("badges") }?.optInt("badges"),
            timeText = summary?.optString("timeText")?.takeIf { it.isNotEmpty() },
            dexCount = summary?.takeIf { it.has("dexCount") }?.optInt("dexCount"),
            savedAtEpochSeconds = meta.takeIf { it.has("savedAt") }?.optLong("savedAt"),
            playTimeSeconds = meta.takeIf { it.has("playTime") }?.optDouble("playTime"),
            format = meta.takeIf { it.has("format") }?.optInt("format"),
        )
    }

    private suspend fun <T> request(
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean = true,
        parse: (JSONObject) -> T,
    ): SyncResult<T> {
        val headers = mutableMapOf("Accept" to "application/json")
        if (body != null) headers["Content-Type"] = "application/json"
        if (authenticated) {
            val auth = credentials() ?: return SyncResult.Unauthorized
            headers["x-sync-account"] = auth.first
            headers["x-sync-token"] = auth.second
        }
        val response = try {
            transport.send(HttpRequest(method, baseUrl.trimEnd('/') + path, headers, body?.toString()))
        } catch (e: SyncNetworkException) {
            return SyncResult.Failed(e.message ?: "The sync request failed")
        } catch (e: Exception) {
            return SyncResult.Failed(e.message ?: "The sync request failed")
        }

        val json = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.code == 401 || response.code == 403) return SyncResult.Unauthorized
        if (response.code == 409) {
            val remote = json?.let { parseSummary(it) }
            return SyncResult.Conflict(json?.optLong("rev")?.takeIf { it > 0 }, remote)
        }
        if (json == null) {
            return SyncResult.Failed(
                if (response.code >= 400) "The server answered ${response.code}"
                else "The server sent an unreadable reply",
                response.code,
            )
        }
        val error = json.optString("error").takeIf { it.isNotEmpty() }
        if (response.code >= 400 || error != null) {
            return SyncResult.Failed(error ?: "The server answered ${response.code}", response.code)
        }
        return try {
            SyncResult.Ok(parse(json))
        } catch (e: JSONException) {
            SyncResult.Failed(e.message ?: "The server sent an unexpected reply", response.code)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Upstream `SyncClient.DEFAULT_URL`. */
        const val DEFAULT_URL = "https://sync.147.182.215.255.sslip.io"

        const val MAX_BLOB_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val TIMEOUT_SECONDS = 25

        /** Upstream `SyncClient.normalizeCode`: exactly eight digits. */
        fun normalizeCode(code: String?): String? {
            val digits = code.orEmpty().filter { it.isDigit() }
            return if (digits.length == 8) digits else null
        }

        fun formatCode(code: String?): String? =
            normalizeCode(code)?.let { "${it.take(4)}-${it.drop(4)}" }
    }
}
