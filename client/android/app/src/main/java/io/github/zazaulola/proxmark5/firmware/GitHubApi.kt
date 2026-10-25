package io.github.zazaulola.proxmark5.firmware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GitHubException(val code: Int, message: String) : IOException(message)

/**
 * The few GitHub REST calls the firmware screen needs, over plain HttpURLConnection.
 * Public reads work without a token (60 requests an hour); [token] raises that limit and
 * is required to start a build.
 */
class GitHubApi(private val token: () -> String?) {

    suspend fun get(path: String): String = withContext(Dispatchers.IO) { request("GET", path, null) }

    suspend fun post(path: String, body: JSONObject): String =
        withContext(Dispatchers.IO) { request("POST", path, body.toString()) }

    /**
     * Downloads a public release file to [dest] (through a .part file) and returns its
     * SHA-256 in hex. No token: release downloads redirect to another host.
     */
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            val c = open(url, auth = false)
            try {
                val code = c.responseCode
                if (code !in 200..299) throw GitHubException(code, "HTTP $code: $url")
                val total = c.contentLengthLong
                val part = File(dest.parentFile, dest.name + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                if (!part.renameTo(dest)) throw IOException("cannot write $dest")
                digest.digest().joinToString("") { "%02x".format(it) }
            } finally {
                c.disconnect()
            }
        }

    private fun request(method: String, path: String, body: String?): String {
        val url = if (path.startsWith("https://")) path else "$API/$path"
        val c = open(url, auth = true)
        try {
            c.requestMethod = method
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw GitHubException(code, describe(code, msg))
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String, auth: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "Proxmark5-Android")
        if (auth) {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            token()?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        }
        return c
    }

    private fun describe(code: Int, msg: String?): String = when (code) {
        401 -> "GitHub: токен не подходит (401)"
        403 -> if (msg?.contains("rate limit", true) == true)
            "GitHub: исчерпан лимит запросов без токена, попробуйте позже или добавьте токен"
        else "GitHub: нет прав (403)${msg?.let { ": $it" } ?: ""}"
        404 -> "GitHub: не найдено (404)${msg?.let { ": $it" } ?: ""}"
        422 -> "GitHub: запрос отклонён (422)${msg?.let { ": $it" } ?: ""}"
        else -> "GitHub: ошибка $code${msg?.let { ": $it" } ?: ""}"
    }

    companion object {
        private const val API = "https://api.github.com"
    }
}
