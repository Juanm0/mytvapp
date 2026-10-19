package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Una lista M3U a mostrar en la app.
 *
 * @param name nombre en la barra lateral.
 * @param icon emoji de la barra lateral.
 * @param homeRows cuántas filas de esta lista aparecen en Inicio.
 * @param priorityGroups grupos que van primero si existen (ej. "Argentina").
 * @param maxPerRow máximo de canales por fila (si un grupo tiene más, se parte en varias filas).
 * @param maxRows máximo de filas de esta lista.
 */
data class M3uSource(
    val name: String,
    val url: String,
    val icon: String = "📺",
    val homeRows: Int = 3,
    val priorityGroups: List<String> = emptyList(),
    val maxPerRow: Int = 60,
    val maxRows: Int = 40,
)

private data class Entry(val group: String, val title: Title)

/**
 * ContentRepository que lee listas IPTV (M3U).
 *
 * Arranque rápido: al iniciar lee el caché del disco (si existe) y muestra
 * todo al instante; en paralelo baja las listas de internet y va publicando
 * cada una apenas termina (no espera a la más lenta). Lo bajado se guarda
 * en el caché para el próximo arranque.
 */
class M3uRepository(
    private val sources: List<M3uSource>,
    private val cacheDir: File,
) : ContentRepository {

    private val _sections = MutableStateFlow<List<Section>>(emptyList())
    override val sections: StateFlow<List<Section>> = _sections.asStateFlow()

    private val _loading = MutableStateFlow(true)
    override val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val lock = Any()
    private val entriesBySource = HashMap<Int, List<Entry>>()
    private val unavailableUrls = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(readUnavailableUrls())
    }
    @Volatile private var byId: Map<String, Title> = emptyMap()
    @Volatile private var searchIndex: List<Pair<String, Title>> = emptyList()

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            _loading.value = true
            try {
                if (_sections.value.isEmpty()) {
                    for (i in sources.indices) {
                        readCache(i)?.let { publish(i, it) }
                    }
                }
                coroutineScope {
                    sources.mapIndexed { i, source ->
                        async {
                            runCatching {
                                val entries = parse(download(source.url), "s$i")
                                if (entries.isNotEmpty()) {
                                    writeCache(i, entries)
                                    publish(i, entries)
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _loading.value = false
            }
        }
    }

    override fun find(id: String): Title? = byId[id]

    /** Oculta del catálogo los streams que ExoPlayer confirma que fallan. */
    override suspend fun markUnavailable(id: String) {
        withContext(Dispatchers.IO) {
            val sourceIndex = sources.indices.firstOrNull { id.startsWith("s$it-") } ?: return@withContext
            synchronized(lock) {
                val current = entriesBySource[sourceIndex] ?: readCache(sourceIndex) ?: return@synchronized
                val failed = current.firstOrNull { it.title.id == id } ?: return@synchronized
                unavailableUrls.add(failed.title.streamUrl)
                writeUnavailableUrls()
                val updated = current.filterNot { it.title.id == id }
                if (updated.size != current.size) {
                    entriesBySource[sourceIndex] = updated
                    writeCache(sourceIndex, updated)
                    val ids = HashMap<String, Title>()
                    val index = ArrayList<Pair<String, Title>>()
                    for (list in entriesBySource.values) {
                        for (entry in list) {
                            ids[entry.title.id] = entry.title
                            index.add(normalize(entry.title.name) to entry.title)
                        }
                    }
                    byId = ids
                    searchIndex = index
                    _sections.value = sources.indices.mapNotNull { n ->
                        entriesBySource[n]?.let { buildSection(n, sources[n], it) }
                    }
                }
            }
        }
    }

    override fun search(query: String, limit: Int): List<Title> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Title>()
        val seen = HashSet<String>()
        for ((name, title) in searchIndex) {
            if (tokens.all { name.contains(it) } && seen.add(title.streamUrl)) {
                out.add(title)
                if (out.size >= limit) break
            }
        }
        return out
    }

    // ---------------------------------------------------------------- publicar

    private fun publish(i: Int, entries: List<Entry>) {
        synchronized(lock) {
            if (entriesBySource[i] == entries) return
            entriesBySource[i] = entries

            val secs = sources.indices.mapNotNull { n ->
                entriesBySource[n]?.let { buildSection(n, sources[n], it) }
            }
            val ids = HashMap<String, Title>()
            val index = ArrayList<Pair<String, Title>>()
            for (list in entriesBySource.values) {
                for (e in list) {
                    ids[e.title.id] = e.title
                    index.add(normalize(e.title.name) to e.title)
                }
            }
            byId = ids
            searchIndex = index
            _sections.value = secs
        }
    }

    private fun buildSection(i: Int, source: M3uSource, entries: List<Entry>): Section {
        val groups = entries.groupBy { it.group }
        val ordered = groups.keys.sortedWith(
            compareBy<String>(
                { it.lowercase() in LAST_GROUPS },
                { g ->
                    val p = source.priorityGroups.indexOfFirst { it.equals(g, ignoreCase = true) }
                    if (p >= 0) p else Int.MAX_VALUE
                },
            ).thenByDescending { groups[it]?.size ?: 0 }
        )
        val categories = ordered.flatMap { g ->
            groups.getValue(g).chunked(source.maxPerRow).mapIndexed { n, chunk ->
                Category(
                    id = "s$i-$g-$n",
                    title = if (n == 0) g else "$g (${n + 1})",
                    items = chunk.map { it.title },
                )
            }
        }.take(source.maxRows)
        return Section("s$i", source.name, source.icon, categories, source.homeRows)
    }

    // ------------------------------------------------------------------- red

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Ignora URLs que ExoPlayer no puede reproducir (YouTube, Twitch). */
    private fun parse(text: String, idPrefix: String): List<Entry> {
        val result = ArrayList<Entry>()
        var attrs: Map<String, String>? = null
        var name = ""
        var counter = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                val (a, n) = parseExtInf(line)
                attrs = a
                name = n
            } else if (line.isEmpty() || line.startsWith("#")) {
                continue
            } else {
                val a = attrs
                if (a != null && name.isNotBlank() && isPlayable(line) && line !in unavailableUrls) {
                    val group = a["group-title"]?.split(";")?.firstOrNull()?.trim()
                        .takeUnless { it.isNullOrBlank() } ?: "Otros"
                    result.add(
                        Entry(
                            group,
                            makeTitle("$idPrefix-${counter++}", name, a["tvg-logo"].orEmpty(), line, group),
                        )
                    )
                }
                attrs = null
            }
        }
        return result
    }

    private fun makeTitle(id: String, name: String, logo: String, url: String, group: String): Title {
        val isVod = isVideoOnDemand(name, group, url)
        return Title(
            id = id,
            name = name,
            description = if (isVod) "Película o serie · $group" else "En vivo · $group",
            posterUrl = logo,
            streamUrl = url,
            isLive = !isVod,
        )
    }

    private fun isVideoOnDemand(name: String, group: String, url: String): Boolean {
        val metadata = normalize("$name $group $url")
        val vodTerms = listOf(
            "movie", "movies", "pelicula", "peliculas", "film", "films",
            "series", "serie", "season", "episode", "episodio", "vod"
        )
        if (vodTerms.any { metadata.contains(it) }) return true
        val path = runCatching { URL(url).path.lowercase() }.getOrDefault("")
        return listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { path.endsWith(it) }
    }

    private fun isPlayable(url: String): Boolean {
        val u = url.lowercase()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        return listOf("youtube.com", "youtu.be", "twitch.tv").none { u.contains(it) }
    }

    /** #EXTINF:-1 key="value" ... ,Nombre  ->  (atributos, nombre) */
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

    // ----------------------------------------------------------------- caché

    private fun cacheFile(i: Int) = File(cacheDir, "catalog_$i.tsv")

    private fun writeCache(i: Int, entries: List<Entry>) {
        runCatching {
            val tmp = File(cacheDir, "catalog_$i.tmp")
            tmp.bufferedWriter().use { w ->
                for (e in entries) {
                    val cols = listOf(e.group, e.title.id, e.title.name, e.title.posterUrl, e.title.streamUrl)
                    w.write(cols.joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
                    w.write("\n")
                }
            }
            tmp.renameTo(cacheFile(i))
        }
    }

    private fun readCache(i: Int): List<Entry>? = runCatching {
        val f = cacheFile(i)
        if (!f.exists()) return@runCatching null
        f.bufferedReader().useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                if (p.size < 5) null else Entry(p[0], makeTitle(p[1], p[2], p[3], p[4], p[0]))
            }.filterNot { it.title.streamUrl in unavailableUrls }.toList()
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun unavailableFile() = File(cacheDir, "unavailable_streams.txt")

    private fun readUnavailableUrls(): List<String> = runCatching {
        val file = unavailableFile()
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()
    }.getOrDefault(emptyList())

    private fun writeUnavailableUrls() {
        runCatching {
            unavailableFile().writeText(unavailableUrls.joinToString("\n"), Charsets.UTF_8)
        }
    }

    // ---------------------------------------------------------------- varios

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()

    private companion object {
        val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        val MARKS = Regex("""\p{Mn}+""")
        val LAST_GROUPS = setOf("undefined", "otros")
    }
}
