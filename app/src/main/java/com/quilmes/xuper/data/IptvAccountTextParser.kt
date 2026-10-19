package com.quilmes.xuper.data

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lee los bloques Host/User/Pass/Expires/M3U pegados tal como los entrega el proveedor. */
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
            block.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT)
                val value = line.substring(separator + 1).trim()
                if (value.isNotBlank()) fields[key] = value
            }
            if (fields.isEmpty()) continue

            val host = fields["host"].orEmpty().trim().trimEnd('/')
            val username = fields["user"] ?: fields["username"].orEmpty()
            val password = fields["pass"] ?: fields["password"].orEmpty()
            val status = fields["status"].orEmpty()
            val expires = fields["expires"].orEmpty()
            var m3u = fields["m3u"].orEmpty()

            // La fecha se compara como ISO yyyy-MM-dd, lo que conserva el orden cronológico.
            if (expires.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && expires < today) {
                expired++
                continue
            }
            if (status.contains("expired", ignoreCase = true) ||
                status.contains("disabled", ignoreCase = true) ||
                status.contains("banned", ignoreCase = true) ||
                status.equals("inactive", ignoreCase = true)) {
                expired++
                continue
            }

            if (m3u.isBlank() && host.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                val normalizedHost = if (host.startsWith("http://", true) || host.startsWith("https://", true)) host else "http://$host"
                m3u = "$normalizedHost/get.php?username=${encode(username)}&password=${encode(password)}&type=m3u_plus"
            }
            val validUrl = m3u.startsWith("http://", true) || m3u.startsWith("https://", true)
            if (!validUrl || username.isBlank() || password.isBlank()) {
                invalid++
                continue
            }
            accounts += Account(host, username, password, status, expires, m3u)
        }

        return Result(accounts.distinctBy { it.m3uUrl }, expired, invalid)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
