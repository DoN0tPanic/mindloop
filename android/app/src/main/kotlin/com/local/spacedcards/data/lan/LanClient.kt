package com.local.spacedcards.data.lan

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class LanClient {
    suspend fun ping(
        host: String,
        port: Int,
    ): Result<PcInfo> = withContext(Dispatchers.IO) {
        execute {
            val normalizedHost = normalizeHost(host)
            val verifiedPort = validatePort(port)
            enforceAllowedHost(normalizedHost, verifiedPort)
            val response = request(
                method = "GET",
                host = normalizedHost,
                port = verifiedPort,
                path = "/ping",
                connectTimeoutMs = 4_000,
                readTimeoutMs = 4_000,
            )
            val json = requireJsonBody(response)
            val app = json.optString("app", "").trim()
            if (app != EXPECTED_APP) {
                throw LanError.WrongApp(app.ifBlank { null })
            }
            val name = json.optString("name", "").trim()
            val version = json.optString("version", "").trim()
            PcInfo(
                name = name.ifBlank { normalizedHost },
                version = version.ifBlank { "?" },
            )
        }
    }

    suspend fun bake(
        host: String,
        port: Int,
        code: String,
        raccoltaName: String,
        lang: String,
        cards: List<LanCard>,
    ): Result<String> = withContext(Dispatchers.IO) {
        execute {
            val normalizedHost = normalizeHost(host)
            val verifiedPort = validatePort(port)
            val normalizedCode = normalizeCode(code)
            enforceAllowedHost(normalizedHost, verifiedPort)
            if (cards.isEmpty()) {
                throw LanError.ServerError("This collection has no cards to send.")
            }

            val payload = JSONObject().apply {
                put("raccolta", raccoltaName)
                put("lang", lang)
                put("cards", JSONArray().apply {
                    cards.forEach { card ->
                        put(
                            JSONObject().apply {
                                put("uid", card.uid)
                                put("front", card.front)
                                put("back", card.back)
                            },
                        )
                    }
                })
            }

            val response = request(
                method = "POST",
                host = normalizedHost,
                port = verifiedPort,
                path = "/bake",
                code = normalizedCode,
                contentType = JSON_CONTENT_TYPE,
                body = payload.toString().encodeToByteArray(),
                connectTimeoutMs = 8_000,
                readTimeoutMs = 15_000,
            )
            when (response.statusCode) {
                HttpURLConnection.HTTP_ACCEPTED -> {
                    val json = requireJsonBody(response)
                    val job = json.optString("job", "").trim()
                    if (job.isBlank()) {
                        throw LanError.ServerError("The PC accepted the request but did not return a job id.")
                    }
                    job
                }
                HttpURLConnection.HTTP_FORBIDDEN -> throw parseForbidden(response)
                HttpURLConnection.HTTP_BAD_REQUEST -> throw parseServerJsonError(
                    response = response,
                    fallback = "The PC rejected the bake request.",
                )
                else -> throw parseUnexpectedStatus(
                    response = response,
                    fallback = "The PC returned HTTP ${response.statusCode} while starting the quiz generation.",
                )
            }
        }
    }

    suspend fun status(
        host: String,
        port: Int,
        code: String,
        job: String,
    ): Result<BakeStatus> = withContext(Dispatchers.IO) {
        execute {
            val normalizedHost = normalizeHost(host)
            val verifiedPort = validatePort(port)
            val normalizedCode = normalizeCode(code)
            enforceAllowedHost(normalizedHost, verifiedPort)
            val response = request(
                method = "GET",
                host = normalizedHost,
                port = verifiedPort,
                path = "/status/${job.trim()}",
                code = normalizedCode,
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
            )
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = requireJsonBody(response)
                    BakeStatus(
                        state = json.optString("state", "").trim(),
                        stage = json.optString("stage", "").trim().ifBlank { null },
                        progress = json.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
                        message = json.optString("message", "").trim().ifBlank { null },
                        error = json.optString("error", "").trim().ifBlank { null },
                        uidMismatchCount = json.optJSONArray("uid_mismatch")?.length() ?: 0,
                    )
                }
                HttpURLConnection.HTTP_FORBIDDEN -> throw parseForbidden(response)
                HttpURLConnection.HTTP_NOT_FOUND -> throw LanError.JobNotFound
                else -> throw parseUnexpectedStatus(
                    response = response,
                    fallback = "The PC returned HTTP ${response.statusCode} while checking the job status.",
                )
            }
        }
    }

    suspend fun downloadResult(
        host: String,
        port: Int,
        code: String,
        job: String,
        into: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        execute {
            val normalizedHost = normalizeHost(host)
            val verifiedPort = validatePort(port)
            val normalizedCode = normalizeCode(code)
            enforceAllowedHost(normalizedHost, verifiedPort)
            val response = request(
                method = "GET",
                host = normalizedHost,
                port = verifiedPort,
                path = "/result/${job.trim()}",
                code = normalizedCode,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 30_000,
            )
            when (response.statusCode) {
                HttpURLConnection.HTTP_OK -> {
                    val bytes = response.bodyBytes
                        ?: throw LanError.ServerError("The PC replied without a .qzd payload.")
                    into.outputStream().buffered().use { output ->
                        output.write(bytes)
                    }
                    into
                }
                HttpURLConnection.HTTP_FORBIDDEN -> throw parseForbidden(response)
                HttpURLConnection.HTTP_CONFLICT -> throw LanError.NotReady
                HttpURLConnection.HTTP_NOT_FOUND -> throw LanError.ServerError(
                    "The PC cannot find the generated quiz file for this job anymore.",
                )
                else -> throw parseUnexpectedStatus(
                    response = response,
                    fallback = "The PC returned HTTP ${response.statusCode} while downloading the quiz file.",
                )
            }
        }
    }

    private fun normalizeHost(rawHost: String): String {
        var host = rawHost.trim()
        if (host.startsWith("https://", ignoreCase = true)) {
            throw LanError.InvalidAddress("Use the local HTTP address shown by the PC server, not HTTPS.")
        }
        if (host.startsWith("http://", ignoreCase = true)) {
            host = host.substring(HTTP_PREFIX.length)
        }
        host = host.substringBefore('/').trim()
        if (host.isBlank()) {
            throw LanError.InvalidAddress("Enter the local address shown by the PC server.")
        }
        if (host.count { it == ':' } == 1) {
            val maybePort = host.substringAfterLast(':')
            if (maybePort.all { it.isDigit() }) {
                host = host.substringBeforeLast(':').trim()
            }
        }
        if (host.isBlank()) {
            throw LanError.InvalidAddress("Enter a valid local network address.")
        }
        return host
    }

    private fun validatePort(port: Int): Int {
        if (port !in 1..65535) {
            throw LanError.InvalidAddress("The port must be between 1 and 65535.")
        }
        return port
    }

    private fun normalizeCode(rawCode: String): String {
        val code = rawCode.trim()
        if (code.length != 6 || code.any { !it.isDigit() }) {
            throw LanError.BadCode("Enter the 6-digit pairing code shown on the PC.")
        }
        return code
    }

    private fun enforceAllowedHost(host: String, port: Int) {
        if (host.equals("localhost", ignoreCase = true)) {
            return
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: UnknownHostException) {
            throw LanError.NotReachable(host, port)
        }
        if (addresses.isEmpty()) {
            throw LanError.NotReachable(host, port)
        }
        val allAllowed = addresses.all { address ->
            address.isLoopbackAddress ||
                address.isAnyLocalAddress ||
                (address is Inet4Address && address.isSiteLocalAddress)
        }
        if (!allAllowed) {
            throw LanError.InvalidAddress(
                "Use only a local network address: 192.168.x.x, 10.x.x.x, 172.16-31.x.x, or localhost.",
            )
        }
    }

    private fun request(
        method: String,
        host: String,
        port: Int,
        path: String,
        code: String? = null,
        contentType: String? = null,
        body: ByteArray? = null,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpResponse {
        val url = URL("http://$host:$port$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json, application/octet-stream")
            if (!code.isNullOrBlank()) {
                setRequestProperty(PAIRING_HEADER, code)
            }
            if (!contentType.isNullOrBlank()) {
                setRequestProperty("Content-Type", contentType)
            }
            if (body != null) {
                doOutput = true
            }
        }

        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body)
                }
            }
            val statusCode = connection.responseCode
            val bytes = try {
                val stream = when {
                    statusCode in 200..299 -> connection.inputStream
                    else -> connection.errorStream
                }
                stream?.use { it.readBytes() }
            } catch (_: IOException) {
                null
            }
            HttpResponse(
                statusCode = statusCode,
                contentType = connection.contentType,
                bodyBytes = bytes,
            )
        } catch (error: SocketTimeoutException) {
            throw LanError.Timeout
        } catch (error: UnknownHostException) {
            throw LanError.NotReachable(host, port)
        } catch (error: IOException) {
            throw LanError.NotReachable(host, port)
        } finally {
            connection.disconnect()
        }
    }

    private fun requireJsonBody(response: HttpResponse): JSONObject {
        val body = response.bodyText()
        if (body.isNullOrBlank()) {
            throw LanError.ServerError("The PC replied with an empty response.")
        }
        return try {
            JSONObject(body)
        } catch (error: JSONException) {
            throw LanError.ServerError(
                "The PC replied with invalid JSON: ${error.message ?: error::class.java.simpleName}.",
            )
        }
    }

    private fun parseForbidden(response: HttpResponse): LanError {
        val errorCode = runCatching { requireJsonBody(response).optString("error", "").trim() }.getOrNull()
        return if (errorCode == "codice-non-valido") {
            LanError.BadCode()
        } else {
            LanError.BadCode("The PC rejected the pairing code.")
        }
    }

    private fun parseServerJsonError(
        response: HttpResponse,
        fallback: String,
    ): LanError {
        val json = runCatching { requireJsonBody(response) }.getOrNull()
        val errorCode = json?.optString("error", "")?.trim().orEmpty()
        return LanError.ServerError(
            errorCode.ifBlank { fallback },
        )
    }

    private fun parseUnexpectedStatus(
        response: HttpResponse,
        fallback: String,
    ): LanError {
        val json = runCatching { requireJsonBody(response) }.getOrNull()
        val detail = json?.optString("error", "")?.trim().orEmpty()
        val message = json?.optString("message", "")?.trim().orEmpty()
        return LanError.ServerError(
            when {
                detail.isNotBlank() -> detail
                message.isNotBlank() -> message
                else -> fallback
            },
        )
    }

    private fun HttpResponse.bodyText(): String? = bodyBytes?.toString(Charsets.UTF_8)

    private inline fun <T> execute(block: () -> T): Result<T> = runCatching(block)

    private data class HttpResponse(
        val statusCode: Int,
        val contentType: String?,
        val bodyBytes: ByteArray?,
    )

    private companion object {
        private const val EXPECTED_APP = "mindloop-baker"
        private const val HTTP_PREFIX = "http://"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val PAIRING_HEADER = "X-Pairing-Code"
    }
}
