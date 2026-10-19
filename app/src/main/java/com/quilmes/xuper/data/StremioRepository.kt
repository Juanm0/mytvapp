package com.quilmes.xuper.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Una fuente directa compatible con Media3, con atribución al addon que la devolvió. */
data class StremioStream(
    val name: String,
    val title: String,
    val url: String,
    val description: String = "",
    val mimeType: String? = null,
    val addonUrl: String = "",
) {
    val qualityRank: Int get() {
        val text = "$name $title $description".lowercase()
        return when {
            QUALITY_4K.containsMatchIn(text) -> 4
            QUALITY_2K.containsMatchIn(text) -> 3
            QUALITY_1080.containsMatchIn(text) -> 2
            QUALITY_HD.containsMatchIn(text) -> 1
            else -> 0
        }
    }

    companion object {
        private val QUALITY_4K = Regex("\\b(2160p|4k|uhd)\\b")
        private val QUALITY_2K = Regex("\\b(1440p|2k)\\b")
        private val QUALITY_1080 = Regex("\\b1080p\\b")
        private val QUALITY_HD = Regex("\\b(720p|hd)\\b")
    }
}

/** Persiste las URLs de manifiesto/base de addons. No instala addons ni asume proveedores. */
class StremioAddonManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xuper-stremio-addons", Context.MODE_PRIVATE)

    fun getAddons(): List<String> = prefs.getStringSet(KEY_ADDONS, emptySet())
        .orEmpty().map { normalizeAddonUrl(it) }.filterNotNull().distinct().sorted()

    fun setAddons(urls: Collection<String>) {
        val cleaned = urls.mapNotNull(::normalizeAddonUrl).distinct().toSet()
        prefs.edit().putStringSet(KEY_ADDONS, cleaned).apply()
    }

    fun addAddon(url: String): Boolean {
        val normalized = normalizeAddonUrl(url) ?: return false
        setAddons(getAddons() + normalized)
        return true
    }

    fun removeAddon(url: String) = setAddons(getAddons().filterNot { it == normalizeAddonUrl(url) })

    private fun normalizeAddonUrl(raw: String): String? {
        val value = raw.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
        if (value.isBlank()) return null
        val parsed = runCatching { URL(value) }.getOrNull() ?: return null
        if (parsed.protocol !in setOf("http", "https") || parsed.host.isBlank() || parsed.userInfo != null) return null
        return value
    }

    companion object { private const val KEY_ADDONS = "addon_urls" }
}

/** Consulta todos los addons en paralelo, aísla fallos individuales y unifica resultados. */
class StremioRepository {
    suspend fun getMovieStreams(addonUrls: List<String>, imdbId: String): List<StremioStream> =
        fetchAll(addonUrls, "movie", imdbId)

    suspend fun getSeriesStreams(addonUrls: List<String>, imdbId: String, season: Int, episode: Int): List<StremioStream> {
        if (season < 0 || episode < 1) return emptyList()
        return fetchAll(addonUrls, "series", "$imdbId:$season:$episode")
    }

    /** Compatibilidad con la llamada anterior de un solo addon. */
    suspend fun getMovieStreams(addonUrl: String, imdbId: String): List<StremioStream> = getMovieStreams(listOf(addonUrl), imdbId)
    suspend fun getSeriesStreams(addonUrl: String, imdbId: String): List<StremioStream> = fetchAll(listOf(addonUrl), "series", imdbId)

    private suspend fun fetchAll(addonUrls: List<String>, type: String, contentId: String): List<StremioStream> {
        if (!contentId.matches(Regex("^tt\\d{7,10}(?::\\d{1,3}:\\d{1,3})?$"))) return emptyList()
        val normalized = addonUrls.mapNotNull(::normalizeAddonUrl).distinct()
        if (normalized.isEmpty()) return emptyList()
        val all = supervisorScope {
            normalized.map { addon ->
                async(Dispatchers.IO) { fetchFromAddon(addon, type, contentId) }
            }.map { task -> runCatching { task.await() }.getOrDefault(emptyList<StremioStream>()) }.flatten()
        }
        // Dedupe URL (case-insensitive host/path, exact query to preserve tokens), keep the best metadata.
        return all.groupBy { canonicalUrlKey(it.url) }
            .values.map { duplicates -> duplicates.maxByOrNull { it.qualityRank }!! }
            .sortedWith(compareByDescending<StremioStream> { it.qualityRank }.thenBy { it.name.lowercase() }.thenBy { it.title.lowercase() })
    }

    private suspend fun fetchFromAddon(addon: String, type: String, contentId: String): List<StremioStream> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL("$addon/stream/$type/$contentId.json")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "XuperQuilmesTV/1.0 AndroidTV")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching emptyList()
                val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                val streams = root.optJSONArray("streams") ?: return@runCatching emptyList()
                buildList {
                    for (i in 0 until streams.length()) {
                        val item = streams.optJSONObject(i) ?: continue
                        // infoHash y externalUrl requieren resolución externa/torrent; no son URLs directas.
                        if (item.has("infoHash") || item.has("externalUrl")) continue
                        val streamUrl = item.optString("url").trim()
                        val parsed = runCatching { URL(streamUrl) }.getOrNull() ?: continue
                        if (parsed.protocol !in setOf("http", "https") || parsed.host.isBlank() || parsed.userInfo != null) continue
                        val name = item.optString("name").ifBlank { URL(addon).host }
                        val title = item.optString("title").ifBlank { name }
                        val hints = item.optJSONObject("behaviorHints")
                        val extension = hints?.optString("filename")?.substringAfterLast('.', "")?.lowercase()
                            ?: streamUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
                        val mime = when (extension) {
                            "m3u8" -> "application/x-mpegURL"
                            "mpd" -> "application/dash+xml"
                            "mp4" -> "video/mp4"
                            else -> null
                        }
                        add(StremioStream(name, title, streamUrl, item.optString("description"), mime, addon))
                    }
                }
            } finally { connection.disconnect() }
        }.getOrDefault(emptyList())
    }

    private fun canonicalUrlKey(raw: String): String = runCatching {
        val parsed = URL(raw)
        "${parsed.protocol.lowercase()}://${parsed.host.lowercase()}${parsed.port.takeIf { it != -1 }?.let { ":$it"}.orEmpty()}${parsed.file}"
    }.getOrDefault(raw)

    private fun normalizeAddonUrl(raw: String): String? {
        val value = raw.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
        if (value.isBlank()) return null
        val parsed = runCatching { URL(value) }.getOrNull() ?: return null
        if (parsed.protocol !in setOf("http", "https") || parsed.host.isBlank() || parsed.userInfo != null) return null
        return value
    }
}
