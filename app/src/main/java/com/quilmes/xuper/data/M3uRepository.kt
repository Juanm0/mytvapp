package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Una lista M3U a mostrar en la app.
 *
 * @param name nombre que se muestra como prefijo de cada fila.
 * @param maxGroups cuántas filas (group-title) mostrar de esta lista.
 * @param priorityGroups grupos que van primero si existen (ej. "Argentina").
 * @param maxPerRow máximo de canales por fila.
 */
data class M3uSource(
    val name: String,
    val url: String,
    val maxGroups: Int = 4,
    val priorityGroups: List<String> = emptyList(),
    val maxPerRow: Int = 40,
)

/**
 * ContentRepository que lee listas IPTV en formato M3U.
 * Descarga todas las listas en paralelo la primera vez y cachea el resultado;
 * si una lista falla, las demás igual se muestran.
 */
class M3uRepository(private val sources: List<M3uSource>) : ContentRepository {

    private val mutex = Mutex()
    private var cache: List<Category>? = null

    private suspend fun load(): List<Category> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            coroutineScope {
                sources.mapIndexed { index, source ->
                    async { runCatching { buildCategories(index, source) }.getOrDefault(emptyList()) }
                }.map { it.await() }.flatten()
            }
        }.also { cache = it }
    }

    override suspend fun getFeatured(): Title? =
        load().flatMap { it.items }.firstOrNull { it.posterUrl.isNotBlank() }

    override suspend fun getCategories(): List<Category> = load()

    private fun buildCategories(sourceIndex: Int, source: M3uSource): List<Category> {
        val entries = parse(download(source.url), "s$sourceIndex")
        val groups = entries.groupBy { it.first }
        val ordered = groups.keys.sortedWith(
            compareBy<String> { g ->
                val p = source.priorityGroups.indexOfFirst { it.equals(g, ignoreCase = true) }
                if (p >= 0) p else Int.MAX_VALUE
            }.thenByDescending { groups[it]?.size ?: 0 }
        ).take(source.maxGroups)

        return ordered.map { group ->
            Category(
                id = "s$sourceIndex-$group",
                title = "${source.name} · $group",
                items = groups.getValue(group).take(source.maxPerRow).map { it.second },
            )
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Devuelve pares (grupo, Title). Ignora URLs que ExoPlayer no puede reproducir (YouTube, Twitch). */
    private fun parse(text: String, idPrefix: String): List<Pair<String, Title>> {
        val result = mutableListOf<Pair<String, Title>>()
        var attrs: Map<String, String>? = null
        var name = ""
        var counter = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val (a, n) = parseExtInf(line)
                    attrs = a
                    name = n
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                attrs != null -> {
                    if (isPlayable(line) && name.isNotBlank()) {
                        val group = attrs["group-title"]?.split(";")?.first()?.trim()
                            .takeUnless { it.isNullOrBlank() } ?: "Otros"
                        result += group to Title(
                            id = "$idPrefix-${counter++}",
                            name = name,
                            posterUrl = attrs["tvg-logo"].orEmpty(),
                            streamUrl = line,
                            isLive = true,
                        )
                    }
                    attrs = null
                }
            }
        }
        return result
    }

    private fun isPlayable(url: String): Boolean {
        val u = url.lowercase()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        return listOf("youtube.com", "youtu.be", "twitch.tv").none { u.contains(it) }
    }

    /** #EXTINF:-1 key="value" ... ,Nombre  →  (atributos, nombre) */
    private fun parseExtInf(line: String): Pair<Map<String, String>, String> {
        var inQuotes = false
        var commaIdx = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) { commaIdx = i; break }
        }
        val head = if (commaIdx >= 0) line.substring(0, commaIdx) else line
        val name = if (commaIdx >= 0) line.substring(commaIdx + 1).trim() else ""
        val attrs = ATTR.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }
        return attrs to name
    }

    private companion object {
        val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    }
}
