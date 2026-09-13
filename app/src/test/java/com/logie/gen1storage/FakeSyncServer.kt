package com.logie.gen1storage

import com.logie.gen1storage.sync.HttpRequest
import com.logie.gen1storage.sync.HttpResponse
import com.logie.gen1storage.sync.SyncNetworkException
import com.logie.gen1storage.sync.SyncTransport
import org.json.JSONObject

/**
 * A stand-in for the Gen1Recomp sync service, implementing the parts of the
 * protocol this app uses — including its revision checks and its failure modes.
 *
 * Tests drive the real [com.logie.gen1storage.sync.SyncApi] against this, so
 * request shapes, header names and status handling are all exercised rather
 * than mocked away.
 */
class FakeSyncServer(
    private val account: String = "acct-1",
    private val token: String = "tok-1",
) : SyncTransport {

    private data class Row(var blob: String, var rev: Long, var meta: JSONObject, val slot: String?)

    private val rows = LinkedHashMap<String, Row>()

    /** Requests seen, as "METHOD /path", for asserting the call sequence. */
    val calls = mutableListOf<String>()

    /** When set, the next matching request fails at the transport level. */
    var failNext: String? = null

    /**
     * When set, the request is applied and *then* reported as a transport
     * failure — the case where a write lands but the answer never arrives.
     */
    var loseReplyFor: String? = null

    /** Refuses every authenticated call, as a revoked token would. */
    var revokeToken: Boolean = false

    fun put(version: String, playthroughId: String, blob: String, slot: String? = "slot1"): Long {
        val key = "$version/$playthroughId"
        val existing = rows[key]
        val rev = (existing?.rev ?: 0L) + 1
        rows[key] = Row(blob, rev, JSONObject().put("playthroughId", playthroughId), slot)
        return rev
    }

    fun blobOf(version: String, playthroughId: String): String? = rows["$version/$playthroughId"]?.blob

    /** The index the server holds beside the bytes, as the game's sync reads it. */
    fun metaOf(version: String, playthroughId: String): JSONObject? =
        rows["$version/$playthroughId"]?.meta
    fun revOf(version: String, playthroughId: String): Long? = rows["$version/$playthroughId"]?.rev

    /** Simulates the game saving over a playthrough while the app holds a read. */
    fun gameSaves(version: String, playthroughId: String, blob: String) {
        put(version, playthroughId, blob)
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        val path = request.url.substringAfter("://").substringAfter('/').let { "/$it" }
        val route = path.substringBefore('?')
        val signature = "${request.method} $route"
        calls += signature

        failNext?.let {
            if (it == signature) {
                failNext = null
                throw SyncNetworkException("the connection dropped")
            }
        }

        if (route != "/sync/link") {
            if (revokeToken ||
                request.headers["x-sync-account"] != account ||
                request.headers["x-sync-token"] != token
            ) {
                return HttpResponse(401, """{"error":"unauthorized"}""")
            }
        }

        val response = when (signature) {
            "POST /sync/link" -> handleLink(request)
            "GET /sync/state" -> HttpResponse(200, stateJson())
            "GET /sync/save" -> handleGet(path)
            "PUT /sync/save" -> handlePut(request)
            "POST /sync/unlink", "POST /sync/codes" -> HttpResponse(200, "{}")
            else -> HttpResponse(404, """{"error":"no such endpoint"}""")
        }

        loseReplyFor?.let {
            if (it == signature) {
                loseReplyFor = null
                throw SyncNetworkException("the reply never arrived")
            }
        }
        return response
    }

    private fun handleLink(request: HttpRequest): HttpResponse {
        val body = JSONObject(request.body.orEmpty())
        if (body.optString("code1") != "49043294" || body.optString("code2") != "09550471") {
            return HttpResponse(401, """{"error":"unauthorized"}""")
        }
        return HttpResponse(
            200,
            JSONObject()
                .put("account", account)
                .put("deviceToken", token)
                .put("device", "device-1")
                .toString(),
        )
    }

    private fun handleGet(path: String): HttpResponse {
        val params = path.substringAfter('?', "").split('&')
            .mapNotNull { it.split('=').takeIf { p -> p.size == 2 } }
            .associate { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") }
        val key = "${params["version"]}/${params["id"]}"
        val row = rows[key] ?: return HttpResponse(404, """{"error":"no such save"}""")
        return HttpResponse(
            200,
            JSONObject().put("blob", row.blob).put("rev", row.rev).put("meta", row.meta).toString(),
        )
    }

    private fun handlePut(request: HttpRequest): HttpResponse {
        val body = JSONObject(request.body.orEmpty())
        val meta = body.optJSONObject("meta") ?: JSONObject()
        val playthroughId = meta.optString("playthroughId")
        val key = "${body.optString("version")}/$playthroughId"
        val row = rows[key]
        val baseRev = if (body.has("baseRev")) body.optLong("baseRev") else null
        // The whole point of baseRev: a write against a stale revision loses.
        if (row != null && baseRev != null && baseRev != row.rev) {
            return HttpResponse(409, JSONObject().put("rev", row.rev).put("meta", row.meta).toString())
        }
        val rev = (row?.rev ?: 0L) + 1
        rows[key] = Row(body.optString("blob"), rev, meta, row?.slot ?: body.optString("slot").takeIf { it.isNotEmpty() })
        return HttpResponse(200, JSONObject().put("rev", rev).toString())
    }

    private fun stateJson(): String {
        val saves = JSONObject()
        rows.forEach { (key, row) ->
            saves.put(
                key,
                JSONObject()
                    .put("rev", row.rev)
                    .put("meta", row.meta)
                    .apply { row.slot?.let { put("slot", it) } },
            )
        }
        return JSONObject()
            .put("saves", saves)
            .put("deleted", JSONObject())
            .put(
                "devices",
                org.json.JSONArray().put(
                    JSONObject().put("id", "device-1").put("label", "test").put("current", true)
                ),
            )
            .toString()
    }
}
