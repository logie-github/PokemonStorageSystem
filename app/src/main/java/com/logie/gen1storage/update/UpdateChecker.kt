package com.logie.gen1storage.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A release newer than the one running. */
data class AvailableUpdate(val version: String, val url: String)

/**
 * Asks GitHub whether there is a newer release.
 *
 * Deliberately only *asks*. The app does not download or install anything
 * itself: that would want the permission to install packages, which is a large
 * thing to hold for a convenience, and an app that can silently replace itself
 * is a worse thing to hand someone than one that opens a page. Saying yes opens
 * the release, and Android's own installer takes it from there.
 */
object UpdateChecker {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/logie-github/PokemonStorageSystem/releases/latest"

    /**
     * Returns the newer release, or null when there is none — and equally when
     * the check simply could not be made. An update check that cannot reach
     * the network is not news.
     */
    fun check(currentVersion: String): AvailableUpdate? = runCatching {
        val connection = (URL(LATEST_RELEASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        val body = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.use { it.readBytes() }.decodeToString()
        } finally {
            connection.disconnect()
        }

        val release = JSONObject(body)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        if (tag.isBlank() || !isNewer(tag, currentVersion)) return null

        // The release page rather than the asset: a person about to install an
        // APK by hand should see what they are installing and its checksum.
        val page = release.optString("html_url").ifBlank { return null }
        AvailableUpdate(tag, page)
    }.getOrNull()

    /**
     * Compares dotted versions a part at a time.
     *
     * Not a string comparison, which would call 2.10.0 older than 2.9.0, and
     * not a float, which would do the same. Anything non-numeric in a part —
     * a `-debug` suffix, an `rc1` — is ignored rather than guessed at.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.trim().split('.').map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }
}
