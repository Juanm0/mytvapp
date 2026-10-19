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
import com.quilmes.xuper.data.ContentKind

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
    /** Nombres que deben aparecer primero dentro de una lista curada. */
    val priorityNames: List<String> = emptyList(),
    /** Para una lista nacional, muestra todos los canales en una única fila ordenada. */
    val flattenIntoOneCategory: Boolean = false,
    val maxPerRow: Int = 60,
    val maxRows: Int = 40,
    /** Hint explícito para listas dedicadas a VOD; null para listas mixtas o de canales. */
    val contentHint: ContentKind? = null,
    /** This source contributes to the curated Argentina row without a separate sidebar section. */
    val mergeIntoArgentina: Boolean = false,
    /** Avoid expensive metadata lookups for huge, mixed legacy playlists. */
    val enrichMetadata: Boolean = true,
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
    private var catalogCategories: List<Category> = emptyList()
    private var searchCatalogTitles: List<Title> = emptyList()
    private val tmdbMetadata = TmdbMetadataService(cacheDir)
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
                                val parsed = parse(download(source.url), "s$i", source.contentHint)
                                if (parsed.isNotEmpty()) {
                                    val enriched = if (tmdbMetadata.enabled && source.enrichMetadata) {
                                        tmdbMetadata.enrich(parsed.map { EntryForMetadata(it.group, it.title) })
                                            .map { Entry(it.group, it.title) }
                                    } else parsed
                                    writeCache(i, enriched)
                                    publish(i, enriched)
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

    /** Añade el catálogo visual de TMDB sin mezclarlo con las fuentes de canales reales. */
    fun setCatalogCategories(categories: List<Category>) {
        synchronized(lock) {
            // Inicio ya no muestra estas filas (lo arma HomeScreen aparte, solo con
            // Argentina/GH/Deportes y el VOD de las listas M3U). Esto se guarda
            // igual para alimentar el buscador: antes se descartaba cualquier título
            // de TMDB sin un enlace real en las listas M3U, así que si no había match
            // la ficha directamente no existía en ningún lado (ni en Inicio ni
            // buscándola). Ahora se guardan todos; resolveCatalogTitle conecta la URL
            // cuando hay una coincidencia real, y si no la hay la ficha igual aparece
            // en el buscador, solo que sin reproducción disponible.
            catalogCategories = categories.mapNotNull { category ->
                val resolved = category.items.map(::resolveCatalogTitle)
                category.copy(items = resolved).takeIf { resolved.isNotEmpty() }
            }
            rebuildPublishedState()
        }
    }

    /** Registra resultados de búsqueda y conecta películas cuando existe una URL M3U coincidente. */
    fun registerCatalogTitles(titles: List<Title>) {
        if (titles.isEmpty()) return
        synchronized(lock) {
            // Antes se descartaba cualquier resultado de TMDB sin enlace reproducible,
            // así que buscar un título que solo estaba en listas que ya no están activas
            // (por ejemplo, tras cambiar las fuentes M3U de esta versión) no devolvía nada.
            // Ahora se guardan todos los resultados; la ficha se puede abrir igual, con
            // el botón de reproducción deshabilitado si no hay ninguna URL real conectada.
            searchCatalogTitles = (titles.map(::resolveCatalogTitle) + searchCatalogTitles).distinctBy { it.id }.take(120)
            rebuildPublishedState()
        }
    }

    private fun resolveCatalogTitle(title: Title): Title {
        if (title.streamUrl.isNotBlank()) return title
        if (title.contentKind == ContentKind.MOVIE) {
            val match = findPlayableMovie(title.name) ?: return title
            return title.copy(streamUrl = match.streamUrl, isLive = false)
        }
        if (title.contentKind == ContentKind.SERIES) {
            val firstEpisode = findFirstPlayableEpisode(title.name) ?: return title
            // El botón principal de la ficha de una serie empieza por su primer episodio disponible.
            return title.copy(streamUrl = firstEpisode.streamUrl, isLive = false)
        }
        return title
    }

    /** Búsqueda pública de película por nombre en todas las listas VOD activas. */
    fun findPlayableMovie(name: String): Title? {
        val target = normalizeContentTitle(name)
        if (target.isBlank()) return null
        return entriesBySource.values.asSequence().flatten().map { it.title }
            .filter { !it.isLive && it.streamUrl.isNotBlank() && it.contentKind in setOf(ContentKind.MOVIE, ContentKind.OTHER_VOD) }
            .map { it to titleMatchScore(target, normalizeContentTitle(it.name)) }
            .filter { it.second >= 0.80 }
            .sortedWith(compareByDescending<Pair<Title, Double>> { it.second }.thenBy { if (it.first.contentKind == ContentKind.MOVIE) 0 else 1 })
            .firstOrNull()?.first
    }

    /** Primer episodio disponible para arrancar una serie desde su ficha. */
    fun findFirstPlayableEpisode(seriesName: String): Title? {
        val target = normalizeContentTitle(seriesName)
        if (target.isBlank()) return null
        return entriesBySource.values.asSequence().flatten().map { it.title }
            .filter { !it.isLive && it.streamUrl.isNotBlank() }
            .mapNotNull { title -> episodeKey(title.name)?.let { key -> title to key } }
            .map { (title, key) -> Triple(title, key, titleMatchScore(target, key.first)) }
            .filter { it.third >= 0.80 }
            .sortedWith(compareBy<Triple<Title, Triple<String, Int, Int>, Double>>({ it.second.second }, { it.second.third }, { -it.third }))
            .firstOrNull()?.first
    }

    private fun titleMatchScore(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (minOf(a.length, b.length) >= 8 && (a.contains(b) || b.contains(a)) && kotlin.math.abs(a.length - b.length) <= 14) return 0.86
        val left = a.split(' ').filter { it.length > 1 }.toSet()
        val right = b.split(' ').filter { it.length > 1 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val overlap = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        val jaccard = overlap / union
        val containment = overlap / minOf(left.size, right.size).toDouble()
        return maxOf(jaccard, if (containment >= 0.8 && overlap >= 2) 0.82 else 0.0)
    }

    /** Alternativas reales de reproducción para el mismo título, ordenadas por fuente. */
    fun getPlayableAlternatives(titleId: String): List<Title> {
        val current = byId[titleId] ?: return emptyList()
        if (current.streamUrl.isBlank()) return emptyList()
        val currentEpisode = episodeKey(current.name) ?: if (current.contentKind == ContentKind.SERIES) {
            entriesBySource.values.asSequence().flatten().map { it.title }
                .mapNotNull { candidate -> episodeKey(candidate.name)?.let { key -> candidate to key } }
                .firstOrNull { (_, key) -> titleMatchScore(normalizeContentTitle(current.name), key.first) >= 0.80 }
                ?.second
        } else null
        val targetName = normalizeContentTitle(current.name)
        val matches = entriesBySource.entries.sortedBy { it.key }.flatMap { (_, entries) ->
            entries.map { it.title }.filter { candidate ->
                if (candidate.streamUrl.isBlank()) return@filter false
                if (current.isLive) {
                    candidate.isLive && titleMatchScore(targetName, normalizeContentTitle(candidate.name)) >= 0.80
                } else if (currentEpisode != null) {
                    if (candidate.isLive) return@filter false
                    val candidateEpisode = episodeKey(candidate.name)
                    candidateEpisode != null && titleMatchScore(currentEpisode.first, candidateEpisode.first) >= 0.80 &&
                        candidateEpisode.second == currentEpisode.second && candidateEpisode.third == currentEpisode.third
                } else {
                    !candidate.isLive && candidate.contentKind in setOf(ContentKind.MOVIE, ContentKind.OTHER_VOD) &&
                        titleMatchScore(targetName, normalizeContentTitle(candidate.name)) >= 0.80
                }
            }
        }.distinctBy { it.streamUrl }
        return matches
    }

    /** Vincula un episodio de TMDB con el stream M3U si coinciden serie, temporada y episodio. */
    fun findPlayableEpisode(seriesName: String, seasonNumber: Int, episodeNumber: Int): Title? {
        val expected = normalizeContentTitle(seriesName)
        if (expected.isBlank()) return null
        return entriesBySource.values.asSequence().flatten().map { it.title }
            .filter { !it.isLive && it.streamUrl.isNotBlank() && it.contentKind in setOf(ContentKind.SERIES, ContentKind.OTHER_VOD) }
            .mapNotNull { title -> episodeKey(title.name)?.let { key -> title to key } }
            .firstOrNull { (_, key) -> titleMatchScore(expected, key.first) >= 0.80 && key.second == seasonNumber && key.third == episodeNumber }
            ?.first
    }

    private fun normalizeContentTitle(raw: String): String = normalize(raw)
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("(?i)\\b(?:2160p|1080p|720p|480p|4k|uhd|fhd|hd|sd|h264|h265|hevc|x264|x265)\\b"), " ")
        .replace(Regex("\\b(?:19|20)\\d{2}\\b"), " ")
        .replace(Regex("[^a-zA-Z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun rebuildPublishedState() {
        val visibleCatalog = catalogCategories.toMutableList()
        if (searchCatalogTitles.isNotEmpty()) {
            visibleCatalog.add(Category("tmdb-search-results", "Descubiertos recientemente", searchCatalogTitles))
        }
        val secs = sources.indices.mapNotNull { n ->
            entriesBySource[n]?.takeUnless { sources[n].mergeIntoArgentina }?.let { buildSection(n, sources[n], it) }
        }.toMutableList()
        if (visibleCatalog.isNotEmpty()) secs.add(Section("tmdb-catalog", "Catálogo", "🎬", visibleCatalog, visibleCatalog.size))
        val ids = HashMap<String, Title>()
        val index = ArrayList<Pair<String, Title>>()
        entriesBySource.values.flatten().forEach { entry ->
            ids[entry.title.id] = entry.title
            index.add(normalize(entry.title.name) to entry.title)
        }
        visibleCatalog.flatMap { it.items }.forEach { title ->
            ids[title.id] = title
            index.add(normalize(title.name) to title)
        }
        byId = ids
        searchIndex = index
        _sections.value = secs
    }

    /** Zapping local: usa la fila/categoría del canal actual (país o grupo), no fuerza Argentina. */
    fun getChannelQueue(titleId: String): List<Title> {
        val current = byId[titleId] ?: return getArgentinaChannels()
        if (!current.isLive) return emptyList()
        val sourceIndex = titleId.substringBefore('-').removePrefix("s").toIntOrNull()
        val argentinaUrls = _sections.value.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
            ?.categories.orEmpty().flatMap { it.items }.map { it.streamUrl }.toSet()
        if ((sourceIndex != null && sources.getOrNull(sourceIndex)?.name.equals("Argentina", ignoreCase = true)) || current.streamUrl in argentinaUrls) {
            return getArgentinaChannels()
        }
        val entries = sourceIndex?.let { entriesBySource[it] }.orEmpty()
        val group = entries.firstOrNull { it.title.id == titleId }?.group
        val sameGroup = entries.filter { it.title.isLive && it.group == group }.map { it.title }
        if (sameGroup.size > 1) return sameGroup.sortedWith(compareBy<Title>({ channelPriority(it.name) }, { normalize(it.name) }))
        val sameSource = entries.filter { it.title.isLive }.map { it.title }
        return if (sameSource.isNotEmpty()) sameSource.sortedWith(compareBy<Title>({ channelPriority(it.name) }, { normalize(it.name) })) else getArgentinaChannels()
    }

    /** Busca el siguiente episodio reproducible de la misma serie si la fuente los etiqueta. */
    fun getNextEpisode(titleId: String): Title? {
        val current = byId[titleId] ?: return null
        if (current.isLive || current.streamUrl.isBlank()) return null
        val currentEpisode = episodeKey(current.name) ?: return null
        val sameSeries = byId.values.asSequence()
            .filter { !it.isLive && it.streamUrl.isNotBlank() && it.contentKind == current.contentKind }
            .mapNotNull { title -> episodeKey(title.name)?.let { key -> if (key.first == currentEpisode.first) title to key else null } }
            .distinctBy { it.first.id }
            .sortedWith(compareBy<Pair<Title, Triple<String, Int, Int>>>({ it.second.second }, { it.second.third }))
            .toList()
        val index = sameSeries.indexOfFirst { it.first.id == titleId }
        return sameSeries.getOrNull(index + 1)?.first
    }

    private fun episodeKey(raw: String): Triple<String, Int, Int>? {
        val patterns = listOf(
            Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b"),
            Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b"),
            Regex("(?i)\\btemporada\\s*(\\d+).*?(?:episodio|episode|ep)\\s*(\\d+)\\b"),
            Regex("(?i)\\b(?:episodio|episode|ep)\\s*(\\d+)\\b"),
        )
        for ((index, regex) in patterns.withIndex()) {
            val match = regex.find(raw) ?: continue
            val season: Int
            val episode: Int
            if (index == 3) { season = 1; episode = match.groupValues[1].toIntOrNull() ?: continue }
            else { season = match.groupValues[1].toIntOrNull() ?: continue; episode = match.groupValues[2].toIntOrNull() ?: continue }
            val prefix = raw.substring(0, match.range.first)
            val suffix = raw.substring(match.range.last + 1)
            val baseRaw = when {
                prefix.isNotBlank() -> prefix
                suffix.isNotBlank() -> suffix
                else -> raw.removeRange(match.range)
            }
            val base = normalizeContentTitle(baseRaw)
            if (base.isNotBlank()) return Triple(base, season, episode)
        }
        return null
    }

    /** Un fallo de reproducción puede ser transitorio: no bloquea ni borra la fuente M3U. */
    override suspend fun markUnavailable(id: String) = Unit

    override fun search(query: String, limit: Int): List<Title> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Title>()
        val seen = HashSet<String>()
        for ((name, title) in searchIndex) {
            if (tokens.all { name.contains(it) } && seen.add(title.streamUrl.ifBlank { title.id })) {
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

            rebuildPublishedState()
        }
    }

    /** Cola de zapping idéntica a la fila Argentina: top 9, todos los GH, luego el resto. */
    fun getArgentinaChannels(): List<Title> {
        val row = _sections.value.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
            ?.categories.orEmpty().flatMap { it.items }
        return row.ifEmpty {
            synchronized(lock) { entriesBySource.values.flatten().map { it.title }.filter { it.isLive } }
                .distinctBy { it.streamUrl }
                .sortedWith(compareBy<Title>({ argentinaRank(it.name) }, { normalize(it.name) }))
        }
    }

    /** Curated order: first nine familiar Argentine channels, then every Gran Hermano feed, then the rest. */
    private fun argentinaRank(rawName: String): Int {
        val n = normalize(rawName)
        return when {
            n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") || "todo noticias" in n -> 0
            "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
            "cronica" in n -> 2
            "el trece" in n || "eltrece" in n -> 3
            "telefe" in n -> 4
            n == "america" || n.startsWith("america ") || "america tv" in n -> 5
            "c5n" in n -> 6
            n.contains("canal 9") || n == "el nueve" || n.startsWith("canal nueve") -> 7
            "tv publica" in n || "television publica" in n -> 8
            n.contains("gran hermano") || n == "gh" -> 9
            else -> 10
        }
    }

    private fun argentinaDedupeKey(title: Title): String {
        val n = normalize(title.name)
        return when {
            n.contains("gran hermano") || n == "gh" -> "gh:${title.streamUrl}"
            n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") || "todo noticias" in n -> "tn"
            "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> "ln"
            "cronica" in n -> "cronica"
            "el trece" in n || "eltrece" in n -> "eltrece"
            "telefe" in n -> "telefe"
            n == "america" || n.startsWith("america ") || "america tv" in n -> "america"
            "c5n" in n -> "c5n"
            n.contains("canal 9") || n == "el nueve" || n.startsWith("canal nueve") -> "canal9"
            "tv publica" in n || "television publica" in n -> "tvpublica"
            else -> "url:${title.streamUrl}"
        }
    }

    private fun buildSection(i: Int, source: M3uSource, entries: List<Entry>): Section {
        if (source.flattenIntoOneCategory) {
            val isArgentina = source.name.equals("Argentina", ignoreCase = true)
            val mergedExtras = if (isArgentina) sources.indices
                .filter { sources[it].mergeIntoArgentina }
                .flatMap { entriesBySource[it].orEmpty() }
                .filter { it.title.isLive } else emptyList()
            val realityEntries = if (isArgentina) {
                entriesBySource.values.flatten().filter { entry ->
                    val n = normalize(entry.title.name)
                    entry.title.isLive && (n.contains("gran hermano") || n == "gh")
                }
            } else emptyList()
            val rawTitles = (entries + mergedExtras + realityEntries).map { it.title }
            val dedupedTitles = if (isArgentina) rawTitles.distinctBy(::argentinaDedupeKey) else rawTitles.distinctBy { it.streamUrl }
            val sorted = dedupedTitles
                .sortedWith(compareBy<Title>({ if (isArgentina) argentinaRank(it.name) else Int.MAX_VALUE }, { normalize(it.name) }))
            val category = Category("s$i-argentina", "Canales argentinos", sorted)
            return Section("s$i", source.name, source.icon, listOf(category), source.homeRows)
        }
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
        val conn = URL(url).openConnection()
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        if (conn is HttpURLConnection) {
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
        }
        return conn.getInputStream().bufferedReader().use { it.readText() }
    }

    /** Ignora URLs que ExoPlayer no puede reproducir (YouTube, Twitch). */
    private fun parse(text: String, idPrefix: String, contentHint: ContentKind? = null): List<Entry> {
        val result = ArrayList<Entry>()
        var attrs: Map<String, String>? = null
        var name = ""
        var pendingHeaders = linkedMapOf<String, String>()
        var counter = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val (a, n) = parseExtInf(line)
                attrs = a
                name = n
                pendingHeaders = linkedMapOf()
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                // Keep the common HTTP options used by M3U streams. Other VLC-only
                // options (subtitle delay, subtitle file, etc.) are intentionally ignored.
                val option = line.substringAfter(':')
                val key = option.substringBefore('=', "").trim().lowercase()
                val value = option.substringAfter('=', "").trim()
                if (value.isNotBlank()) when (key) {
                    "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                    "http-user-agent" -> pendingHeaders["User-Agent"] = value
                    "http-origin" -> pendingHeaders["Origin"] = value
                }
            } else if (line.isEmpty() || line == "#EXTM3U" || line.startsWith("#EXTGRP")) {
                continue
            } else {
                // Some lists incorrectly prefix a valid URL with an extra '#': ##https://...
                // Treat that common malformed form as a URL rather than silently dropping it.
                val streamLine = if (line.startsWith("##http://", true) || line.startsWith("##https://", true)) line.drop(1) else line
                if (streamLine.startsWith("#")) continue
                val a = attrs
                if (a != null && name.isNotBlank() && isPlayable(streamLine)) {
                    val rawGroup = a["group-title"]?.split(";")?.firstOrNull()?.trim()
                    val group = rawGroup.takeUnless {
                        it.isNullOrBlank() || it.equals("N/A", ignoreCase = true) ||
                            it.equals("NA", ignoreCase = true) || it.equals("undefined", ignoreCase = true)
                    } ?: "Otros"
                    result.add(
                        Entry(
                            group,
                            makeTitle("$idPrefix-${counter++}", name, a["tvg-logo"].orEmpty(), streamLine, group, contentHint)
                                .copy(requestHeaders = pendingHeaders.toMap()),
                        )
                    )
                }
                attrs = null
                name = ""
                pendingHeaders = linkedMapOf()
            }
        }
        return result
    }

    private fun makeTitle(id: String, name: String, logo: String, url: String, group: String, contentHint: ContentKind? = null): Title {
        val detectedKind = classifyContent(name, group, url)
        // En una lista VOD explícita, las entradas ambiguas son bajo demanda;
        // si nombre/ruta identifica serie o película, se conserva esa clasificación.
        val kind = when {
            contentHint == ContentKind.OTHER_VOD && detectedKind == ContentKind.LIVE -> ContentKind.OTHER_VOD
            contentHint != null -> contentHint
            else -> detectedKind
        }
        val live = kind == ContentKind.LIVE
        val kindLabel = when (kind) {
            ContentKind.LIVE -> "TV en vivo"
            ContentKind.MOVIE -> "Película"
            ContentKind.SERIES -> "Serie / episodio"
            ContentKind.OTHER_VOD -> "Contenido bajo demanda"
        }
        return Title(
            id = id,
            name = name,
            description = "$kindLabel · $group",
            posterUrl = logo,
            streamUrl = url,
            isLive = live,
            contentKind = kind,
        )
    }

    /**
     * Clasificación conservadora: nunca considerar VOD solo porque el grupo se llame
     * "Películas" o porque el nombre contenga "cine". Muchas listas agrupan canales
     * lineales de cine en esas categorías. Se buscan señales fuertes en ruta, formato,
     * temporada/episodio o etiquetas inequívocas de VOD.
     */
    private fun channelPriority(name: String): Int {
        val n = normalize(name)
        return when {
            n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> 0
            "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
            "cronica" in n -> 2
            "eltrece" in n || "el trece" in n -> 3
            "telefe" in n -> 4
            "america" in n -> 5
            "gran hermano" in n || n == "gh" -> 6
            else -> 100
        }
    }

    private fun classifyContent(name: String, group: String, url: String): ContentKind {
        val n = normalize(name)
        val g = normalize(group)
        val path = normalize(runCatching { URL(url).path }.getOrDefault(url))
        val metadata = "$n $g $path"

        val seriesPath = Regex("(^|/)(series|serie|shows|tvshows)(/|$)").containsMatchIn(path)
        val episodePattern = Regex("\\b(s\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|episode\\s*\\d+|episodio\\s*\\d+|ep\\s*\\d+)\\b")
        val seriesWords = listOf("series vod", "tv series", "series on demand", "series a la carta", "episodios", "episodes")
        val seriesGroup = g in setOf("series", "serie", "series tv", "episodios", "episodes", "series completas", "tv shows", "temporadas")
        if (seriesPath || episodePattern.containsMatchIn(n) || seriesGroup || seriesWords.any { it in metadata }) {
            return ContentKind.SERIES
        }

        // Besides generic names, many M3U VOD lists use editorial groups like
        // "TERBARU", "HITS", "BEST OF", "MARVEL" or "Box office". Their URLs
        // often hide behind short redirect paths without a .mp4 extension, so relying
        // only on the URL made these movies incorrectly appear as live TV channels.
        val movieGroup = g in setOf(
            "peliculas", "pelicula", "movies", "movie", "films", "film", "largometrajes",
            "peliculas completas", "movies vod", "terbaru", "hits", "best of", "marvel",
            "netflix", "box office", "pantasi", "fantasi", "twilight saga", "transformers",
            "komik", "perang jadul", "china dll", "vidio.com"
        )
        if (movieGroup) return ContentKind.MOVIE

        val moviePath = Regex("(^|/)(movie|movies|peliculas|films)(/|$)").containsMatchIn(path)
        val videoExtension = listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { path.substringBefore('?').endsWith(it) }
        val explicitVodGroup = listOf("vod", "on demand", "a la carta", "peliculas completas", "full movies", "movies vod").any { it in g }
        val movieTitle = listOf("pelicula completa", "full movie", "movie:", "pelicula:").any { n.startsWith(it) }
        if (moviePath || videoExtension || explicitVodGroup || movieTitle) return ContentKind.MOVIE

        val explicitVod = listOf("conferencia grabada", "contenido bajo demanda", "vod").any { it in metadata } ||
            path.contains("/vod")
        if (explicitVod) return ContentKind.OTHER_VOD
        return ContentKind.LIVE
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

    private fun cacheFile(i: Int): File {
        val sourceName = sources.getOrNull(i)?.name.orEmpty().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "source" }
        return File(cacheDir, "catalog_${i}_${sourceName}.tsv")
    }

    private fun writeCache(i: Int, entries: List<Entry>) {
        runCatching {
            val tmp = File(cacheDir, "catalog_$i.tmp")
            tmp.bufferedWriter().use { w ->
                for (e in entries) {
                    val encodedHeaders = e.title.requestHeaders.entries.joinToString("&") { (k, v) ->
                        java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
                    }
                    val cols = listOf(e.group, e.title.id, e.title.name, e.title.posterUrl, e.title.streamUrl,
                        e.title.backdropUrl, e.title.description, e.title.year.orEmpty(), e.title.rating.orEmpty(), e.title.contentKind.name, encodedHeaders)
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
                if (p.size < 5) null else {
                    val base = makeTitle(p[1], p[2], p[3], p[4], p[0], sources.getOrNull(i)?.contentHint)
                    Entry(p[0], if (p.size >= 10) base.copy(
                        backdropUrl = p[5].ifBlank { base.backdropUrl }, description = p[6].ifBlank { base.description },
                        year = p[7].ifBlank { null }, rating = p[8].ifBlank { null },
                        contentKind = runCatching { ContentKind.valueOf(p[9]) }.getOrDefault(base.contentKind),
                        isLive = runCatching { ContentKind.valueOf(p[9]) }.getOrDefault(base.contentKind) == ContentKind.LIVE,
                        requestHeaders = p.getOrNull(10).orEmpty().split('&').mapNotNull { pair ->
                            if (pair.isBlank() || !pair.contains('=')) null else runCatching {
                                java.net.URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                                    java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                            }.getOrNull()
                        }.toMap(),
                    ) else base)
                }
            }.toList()
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---------------------------------------------------------------- varios

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()

    private companion object {
        val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        val MARKS = Regex("""\p{Mn}+""")
        val LAST_GROUPS = setOf("undefined", "otros")
    }
}
