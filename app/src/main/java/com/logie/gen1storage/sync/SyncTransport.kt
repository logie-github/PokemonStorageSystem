package com.logie.gen1storage.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class HttpResponse(val code: Int, val body: String)

/**
 * The one place this app touches the network.
 *
 * It is an interface so every layer above it — the API client, the save
 * catalogue, the transfer engine — is testable against a scripted server
 * rather than the real one. Nothing else in the app performs I/O over HTTP.
 */
interface SyncTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Thrown for transport-level failures; an HTTP status is never an exception. */
class SyncNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * `HttpURLConnection` on the IO dispatcher. Deliberately dependency-free: the
 * protocol is a handful of JSON requests and adding an HTTP stack for it would
 * be more moving parts than the job needs.
 */
class UrlSyncTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = SyncApi.TIMEOUT_SECONDS * 1000,
) : SyncTransport {

    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SyncNetworkException("Could not reach the sync server: ${e.message}", e)
        }
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doInput = true
            connection.instanceFollowRedirects = true
            request.headers.forEach(connection::setRequestProperty)
            if (request.body != null) {
                connection.doOutput = true
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            // An error status still carries a JSON body the server wants read,
            // and it arrives on the error stream rather than the input stream.
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (body.length > SyncApi.MAX_RESPONSE_BYTES) {
                throw SyncNetworkException("The sync server sent an unexpectedly large reply")
            }
            HttpResponse(code, body)
        } catch (e: SyncNetworkException) {
            throw e
        } catch (e: Exception) {
            throw SyncNetworkException(e.message ?: "The sync request failed", e)
        } finally {
            connection.disconnect()
        }
    }
}
