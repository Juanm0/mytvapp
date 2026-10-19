package com.quilmes.xuper.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lee bloques Host/User/Pass/Expires/M3U y también URLs M3U pegadas directamente, una por línea. */
object IptvAccountTextParser {
    data class Account(
        val host: String,
        val username: String,
        val password: String,
        val status: String,
        val expires: String,
        val m3uUrl: String,
    )

    data class Result(val accounts: List<Account>, val expiredCount: Int, val invalidCount: Int)

    fun parse(rawText: String, today: String = todayIso()): Result {
        val blocks = rawText.split(Regex("(?m)^\\s*-{10,}\\s*$"))
        val accounts = mutableListOf<Account>()
        var expired = 0
        var invalid = 0

        for (block in blocks) {
            val fields = linkedMapOf<String, String>()
            val directUrls = mutableListOf<String>()
            block.lineSequence().forEach { rawLine ->
                val line = rawLine.trim().removePrefix("\uFEFF")
                if (line.isBlank() || line.startsWith("#")) return@forEach
                if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                    directUrls += line
                    return@forEach
                }
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim()
                if (value.isNotBlank()) fields[key] = value
            }

            val status = fields["status"].orEmpty()
            val expires = fields["expires"].orEmpty()
            if (isExpired(expires, status, today)) {
                expired += maxOf(1, directUrls.size)
                continue
            }

            // Acepta URLs completas pegadas directamente, una por línea, incluso sin separadores.
            for (url in directUrls) {
                val account = accountFromUrl(url, status, expires)
                if (account == null) invalid++ else accounts += account
            }

            // También acepta el formato descriptivo Host/User/Pass/M3U.
            val host = fields["host"].orEmpty().trim().trimEnd('/')
            val username = fields["user"] ?: fields["username"].orEmpty()
            val password = fields["pass"] ?: fields["password"].orEmpty()
            var m3u = fields["m3u"].orEmpty()

            if (m3u.isBlank() && directUrls.isEmpty() && host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                val normalizedHost = if (host.startsWith("http://", true) || host.startsWith("https://", true)) host else "http://$host"
                m3u = "$normalizedHost/get.php?username=${encode(username)}&password=${encode(password)}&type=m3u_plus"
            }
            if (directUrls.isEmpty() && (m3u.isNotBlank() || username.isNotBlank() || password.isNotBlank() || host.isNotBlank())) {
                val account = if (m3u.isNotBlank()) accountFromUrl(m3u, status, expires) else null
                if (account != null) accounts += account
                else if (m3u.isNotBlank() || username.isBlank() || password.isBlank()) invalid++
                else accounts += Account(host, username, password, status, expires, m3u)
            }
        }

        return Result(accounts.distinctBy { it.m3uUrl }, expired, invalid)
    }

    private fun accountFromUrl(url: String, status: String, expires: String): Account? = runCatching {
        val uri = URI(url.trim())
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val idx = part.indexOf('=')
            val key = decode(if (idx >= 0) part.substring(0, idx) else part).lowercase(Locale.ROOT)
            val value = decode(if (idx >= 0) part.substring(idx + 1) else "")
            key to value
        }.toMap()
        val username = params["username"] ?: params["user"] ?: return null
        val password = params["password"] ?: params["pass"] ?: return null
        if (username.isBlank() || password.isBlank()) return null
        val host = buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port >= 0) append(":").append(uri.port)
        }
        Account(host, username, password, status, expires, url.trim())
    }.getOrNull()

    private fun isExpired(expires: String, status: String, today: String): Boolean {
        val dateExpired = expires.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && expires < today
        val disabled = status.contains("expired", ignoreCase = true) ||
            status.contains("disabled", ignoreCase = true) ||
            status.contains("banned", ignoreCase = true) ||
            status.equals("inactive", ignoreCase = true)
        return dateExpired || disabled
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
    private fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
