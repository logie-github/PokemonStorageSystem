package com.logie.gen1storage.backup

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The same backup [BackupExport] builds, put straight into the player's own
 * Google Drive — signed in, uploaded, done — rather than handed to a file
 * picker and left to whichever app answers it.
 *
 * The scope asked for is `drive.file`: this app can only ever see a file it
 * created itself, never the rest of somebody's Drive, which is the whole of
 * why Google does not put that scope through the extra verification a
 * broader one would need. One file, updated in place — [findExisting] looks
 * for it by the name this class always uses, so backing up twice replaces
 * the file it made the first time rather than leaving a trail of them.
 *
 * Getting the access token this talks with is the caller's job — see
 * `DriveAuthorization` in the UI layer, which is where an `Activity` is
 * available to ask the player for it. Everything here assumes that part is
 * already done and takes the token as a plain string.
 */
object DriveBackup {

    /** What this app asks Drive for: only files it made itself. */
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    /** One file, always this name, so a second backup replaces the first. */
    const val FILE_NAME = "PokeStorage backup.pssbackup"

    private const val MIME_TYPE = "application/zip"
    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 30_000

    /** The id of the file this app already made, if Drive still has it. */
    fun findExisting(accessToken: String): String? {
        val query = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$query&spaces=drive&fields=files(id)&pageSize=1"
        val response = request(url, "GET", accessToken)
        // Hand-picked rather than pulled in a JSON library for one field:
        // {"files":[{"id":"..."}]} or {"files":[]}.
        return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(response).let { it?.groupValues?.get(1) }
    }

    /** Creates the file the first time, or replaces its contents after that. */
    fun upload(accessToken: String, existingFileId: String?, bytes: ByteArray): String =
        if (existingFileId != null) replaceContent(accessToken, existingFileId, bytes)
        else createWithContent(accessToken, bytes)

    /** The bytes of the file this app made, read straight back. */
    fun download(accessToken: String, fileId: String): ByteArray {
        val connection = open("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", "GET", accessToken)
        return try {
            check(connection, "download the backup")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun createWithContent(accessToken: String, bytes: ByteArray): String {
        val boundary = "pokestorage-${System.currentTimeMillis()}"
        val metadata = "{\"name\":\"${FILE_NAME.replace("\"", "\\\"")}\"}"
        val prefix = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(MIME_TYPE).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val connection = open(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            "POST",
            accessToken,
        )
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        connection.doOutput = true
        val body = prefix + bytes + suffix
        return try {
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection, "create the backup")
            val response = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(response)?.groupValues?.get(1)
                ?: throw IOException("Drive did not say what it named the backup")
        } finally {
            connection.disconnect()
        }
    }

    private fun replaceContent(accessToken: String, fileId: String, bytes: ByteArray): String {
        // Drive's update call is PATCH, and Android's own HttpURLConnection
        // refuses to send that method at all (a long-standing platform
        // restriction, not a version-specific bug) — so this sends a POST
        // carrying the method it actually means, which is the override
        // Google's own client libraries use for the same reason.
        val connection = open(
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media",
            "POST",
            accessToken,
        )
        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        connection.setRequestProperty("Content-Type", MIME_TYPE)
        connection.doOutput = true
        return try {
            writeBody(connection, bytes)
            check(connection, "update the backup")
            fileId
        } finally {
            connection.disconnect()
        }
    }

    private fun writeBody(connection: HttpURLConnection, bytes: ByteArray) {
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
    }

    private fun request(url: String, method: String, accessToken: String): String {
        val connection = open(url, method, accessToken)
        return try {
            check(connection, "reach Drive")
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, accessToken: String): HttpURLConnection {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw IOException("Could not reach Google Drive: ${e.message}", e)
        }
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.doInput = true
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        return connection
    }

    private fun check(connection: HttpURLConnection, doingWhat: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            throw IOException("Could not $doingWhat (HTTP $code): ${detail.take(500)}")
        }
    }
}
