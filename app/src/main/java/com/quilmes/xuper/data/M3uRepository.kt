package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
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
    /** User-supplied IPTV accounts used as ordered per-content fallback sources. */
    val isUserIptvAccount: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Normalización de texto. TODAS las expresiones regulares están compiladas una sola vez acá:
// antes se compilaban de nuevo en cada llamada (miles de veces por título), y eso era lo que
// congelaba la app al arrancar.
// ---------------------------------------------------------------------------------------------

private val MARKS_RE = Regex("\\p{Mn}+")
private val BRACKETS_RE = Regex("\\[[^]]*]")
private val QUALITY_RE = Regex("(?i)\\b(?:2160p|1080p|720p|480p|4k|uhd|fhd|hd|sd|h264|h265|hevc|x264|x265)\\b")
private val YEAR_RE = Regex("\\b(?:19|20)\\d{2}\\b")
private val NON_ALNUM_RE = Regex("[^a-zA-Z0-9 ]")
private val SPACES_RE = Regex("\\s+")
private val EPISODE_PATTERNS = listOf(
    Regex("(?i)\\bS(\\d{1,2})\\s*E(\\d{1,3})\\b"),
    Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b"),
    Regex("(?i)\\btemporada\\s*(\\d+).*?(?:episodio|episode|ep)\\s*(\\d+)\\b"),
    Regex("(?i)\\b(?:episodio|episode|ep)\\s*(\\d+)\\b"),
)
private val SERIES_PATH_RE = Regex("(^|/)(series|serie|shows|tvshows)(/|$)")
private val EPISODE_NAME_RE = Regex("\\b(s\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|episode\\s*\\d+|episodio\\s*\\d+|ep\\s*\\d+)\\b")
private val MOVIE_PATH_RE = Regex("(^|/)(movie|movies|peliculas|films)(/|$)")
private val CACHE_NAME_RE = Regex("[^a-z0-9]+")
private val SOURCE_INDEX_RE = Regex("^s(\\d+)-")

private fun normalizeText(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS_RE, "").lowercase().trim()

/** Título "limpio" para comparar: sin calidad, año, corchetes ni símbolos. */
private fun contentTitleOf(raw: String): String = normalizeText(raw)
    .replace(BRACKETS_RE, " ")
    .replace(QUALITY_RE, " ")
    .replace(YEAR_RE, " ")
    .replace(NON_ALNUM_RE, " ")
    .replace(SPACES_RE, " ").trim()

/** Texto comparable + sus palabras, calculado una sola vez. */
private class ContentKey(val text: String) {
    val tokens: Set<String> = text.split(' ').filter { it.length > 1 }.toSet()
}

private class EpisodeInfo(val series: ContentKey, val season: Int, val episode: Int)

private fun episodeOf(raw: String): EpisodeInfo? {
    for ((index, regex) in EPISODE_PATTERNS.withIndex()) {
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
        val base = contentTitleOf(baseRaw)
        if (base.isNotBlank()) return EpisodeInfo(ContentKey(base), season, episode)
    }
    return null
}

private fun matchScore(a: ContentKey, b: ContentKey): Double {
    val x = a.text
    val y = b.text
    if (x.isBlank() || y.isBlank()) return 0.0
    if (x == y) return 1.0
    if (minOf(x.length, y.length) >= 8 && (x.contains(y) || y.contains(x)) && kotlin.math.abs(x.length - y.length) <= 14) return 0.86
    val left = a.tokens
    val right = b.tokens
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var overlap = 0
    for (t in left) if (t in right) overlap++
    if (overlap == 0) return 0.0
    val union = left.size + right.size - overlap
    val jaccard = overlap.toDouble() / union.toDouble()
    val containment = overlap.toDouble() / minOf(left.size, right.size).toDouble()
    return maxOf(jaccard, if (containment >= 0.8 && overlap >= 2) 0.82 else 0.0)
}

/** Una entrada de lista con todo lo pesado ya precalculado (se hace una vez, en segundo plano). */
private data class Entry(val group: String, val title: Title, val norm: String) {
    val key: ContentKey = ContentKey(contentTitleOf(title.name))
    val episode: EpisodeInfo? = episodeOf(title.name)
}

private fun entryOf(group: String, title: Title) = Entry(group, title, normalizeText(title.name))

private fun isGranHermanoNorm(n: String) = n.contains("gran hermano") || n == "gh"

/**
 * ContentRepository que lee listas IPTV/VOD (M3U) y las mezcla con el catálogo visual de TMDB.
 *
 * Reglas de rendimiento (la app se congelaba al arrancar por romper estas reglas):
 *  - NADA pesado corre en el hilo principal: refresh() y las funciones que reciben datos de TMDB
 *    se llaman desde hilos de fondo.
 *  - Los textos normalizados, tokens y claves de episodio se calculan UNA vez por entrada.
 *  - Las fichas de TMDB se vinculan con un stream M3U de forma perezosa (solo cuando se abren o
 *    aparecen en una búsqueda), no las ~400 juntas al cargar.
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
    private val entriesBySource = HashMap<Int, List<Entry>>() // protegido por `lock`
    @Volatile private var allEntries: List<Entry> = emptyList()
    @Volatile private var catalogCategories: List<Category> = emptyList()
    @Volatile private var searchCatalogTitles: List<Title> = emptyList()
    private val resolvedCatalog = ConcurrentHashMap<String, Title>()
    private val tmdbMetadata = TmdbMetadataService(cacheDir)
    @Volatile private var byId: Map<String, Title> = emptyMap()
    @Volatile private var searchIndex: List<Pair<String, Title>> = emptyList()

    override suspend fun refresh() {
        withContext(Dispatchers.IO) {
            _loading.value = true
            try {
                if (_sections.value.isEmpty()) {
                    // Primero el caché del disco, TODO junto y con una sola publicación.
                    val cached = sources.indices.mapNotNull { i -> readCache(i)?.let { i to it } }
                    if (cached.isNotEmpty()) {
                        synchronized(lock) {
                            cached.forEach { (i, entries) -> entriesBySource[i] = entries }
                            rebuildPublishedState()
                        }
                    }
                }
                // No abrir decenas de conexiones simultáneas: límite pequeño para evitar saturar
                // el dispositivo o proveedores lentos. Un fallo aislado no cancela las demás listas.
                val networkSlots = Semaphore(3)
                coroutineScope {
                    sources.mapIndexed { i, source ->
                        async(Dispatchers.IO) {
                            networkSlots.withPermit {
                                runCatching {
                                    val parsed = parse(downloadWithRetry(source.url), "s$i", source.contentHint)
                                    if (parsed.isNotEmpty()) {
                                        val enriched = if (tmdbMetadata.enabled && source.enrichMetadata) {
                                            tmdbMetadata.enrich(parsed.map { EntryForMetadata(it.group, it.title) })
                                                .map { entryOf(it.group, it.title) }
                                        } else parsed
                                        writeCache(i, enriched)
                                        publish(i, enriched)
                                    } else {
                                        android.util.Log.w("XuperM3U", "Lista sin entradas válidas: ${source.name}")
                                    }
                                }.onFailure { error ->
                                    // El catálogo cacheado se conserva si el proveedor falla o expiró.
                                    android.util.Log.w("XuperM3U", "No se pudo actualizar la fuente ${source.name}; se conserva el caché si existe", error)
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

    /**
     * Devuelve el título; si es una ficha de TMDB sin video, intenta vincularle un stream M3U
     * (una sola búsqueda, con resultado cacheado).
     */
    override fun find(id: String): Title? = byId[id]?.let(::resolveForDisplay)

    /** Guarda el catálogo visual de TMDB. Llamar desde un hilo de fondo. */
    fun setCatalogCategories(categories: List<Category>) {
        synchronized(lock) {
            catalogCategories = categories.filter { it.items.isNotEmpty() }
            rebuildPublishedState()
        }
    }

    /** Registra resultados de búsqueda de TMDB para que el buscador los encuentre. Llamar desde un hilo de fondo. */
    fun registerCatalogTitles(titles: List<Title>) {
        if (titles.isEmpty()) return
        synchronized(lock) {
            val merged = (titles + searchCatalogTitles).distinctBy { it.id }.take(120)
            if (merged == searchCatalogTitles) return
            searchCatalogTitles = merged
            rebuildPublishedState()
        }
    }

    private fun resolveForDisplay(title: Title): Title =
        if (title.streamUrl.isBlank() && title.id.startsWith("tmdb-")) {
            resolvedCatalog.getOrPut(title.id) { resolveCatalogTitle(title) }
        } else title

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
        val target = ContentKey(contentTitleOf(name))
        if (target.text.isBlank()) return null
        var best: Title? = null
        var bestScore = 0.0
        var bestRank = 2
        for (e in allEntries) {
            val t = e.title
            if (t.isLive || t.streamUrl.isBlank()) continue
            if (t.contentKind != ContentKind.MOVIE && t.contentKind != ContentKind.OTHER_VOD) continue
            val score = matchScore(target, e.key)
            if (score < 0.80) continue
            val rank = if (t.contentKind == ContentKind.MOVIE) 0 else 1
            if (score > bestScore || (score == bestScore && rank < bestRank)) {
                best = t; bestScore = score; bestRank = rank
            }
        }
        return best
    }

    /** Primer episodio disponible para arrancar una serie desde su ficha. */
    fun findFirstPlayableEpisode(seriesName: String): Title? {
        val target = ContentKey(contentTitleOf(seriesName))
        if (target.text.isBlank()) return null
        var best: Title? = null
        var bestSeason = Int.MAX_VALUE
        var bestEpisode = Int.MAX_VALUE
        var bestScore = 0.0
        for (e in allEntries) {
            val ep = e.episode ?: continue
            val t = e.title
            if (t.isLive || t.streamUrl.isBlank()) continue
            val score = matchScore(target, ep.series)
            if (score < 0.80) continue
            val better = ep.season < bestSeason ||
                (ep.season == bestSeason && (ep.episode < bestEpisode || (ep.episode == bestEpisode && score > bestScore)))
            if (better) { best = t; bestSeason = ep.season; bestEpisode = ep.episode; bestScore = score }
        }
        return best
    }

    /** Alternativas reales del mismo contenido. Si hay cuentas manuales, usa SOLO esas
     * fuentes y devuelve como máximo una alternativa por lista, en el orden configurado. */
    fun getPlayableAlternatives(titleId: String): List<Title> =
        (byId[titleId]?.let { getPlayableAlternatives(it) }).orEmpty()

    /** Variante que también acepta fichas TMDB ya vinculadas a un stream reproducible. */
    fun getPlayableAlternatives(current: Title): List<Title> {
        if (current.streamUrl.isBlank() && !current.id.startsWith("tmdb-")) return emptyList()
        val all = allEntries
        val accountIndices = sources.indices.filter { sources[it].isUserIptvAccount }.toSet()
        val useAccountSources = accountIndices.isNotEmpty()
        val entries = if (useAccountSources) all.filter { sourceIndexOf(it.title.id) in accountIndices } else all
        val currentKey = ContentKey(contentTitleOf(current.name))
        val currentEpisode: EpisodeInfo? = episodeOf(current.name) ?: if (current.contentKind == ContentKind.SERIES) {
            entries.firstNotNullOfOrNull { e -> e.episode?.takeIf { matchScore(currentKey, it.series) >= 0.80 } }
        } else null
        val matches = entries.filter { e ->
            val candidate = e.title
            if (candidate.streamUrl.isBlank()) return@filter false
            if (current.isLive) {
                candidate.isLive && matchScore(currentKey, e.key) >= 0.80
            } else if (currentEpisode != null) {
                if (candidate.isLive) return@filter false
                val ce = e.episode
                ce != null && ce.season == currentEpisode.season && ce.episode == currentEpisode.episode &&
                    matchScore(currentEpisode.series, ce.series) >= 0.80
            } else {
                !candidate.isLive &&
                    (candidate.contentKind == ContentKind.MOVIE || candidate.contentKind == ContentKind.OTHER_VOD) &&
                    matchScore(currentKey, e.key) >= 0.80
            }
        }
        // Una alternativa por fuente: lista 1, después lista 2, después lista 3.
        val seenSources = HashSet<Int>()
        val ordered = matches.sortedBy { sourceIndexOf(it.title.id) }
            .filter { seenSources.add(sourceIndexOf(it.title.id)) }
            .map { it.title }
        return ordered.distinctBy { it.streamUrl }
    }

    private fun sourceIndexOf(titleId: String): Int =
        SOURCE_INDEX_RE.find(titleId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /** Vincula un episodio de TMDB con el stream M3U si coinciden serie, temporada y episodio. */
    fun findPlayableEpisode(seriesName: String, seasonNumber: Int, episodeNumber: Int): Title? {
        val expected = ContentKey(contentTitleOf(seriesName))
        if (expected.text.isBlank()) return null
        for (e in allEntries) {
            val ep = e.episode ?: continue
            if (ep.season != seasonNumber || ep.episode != episodeNumber) continue
            val t = e.title
            if (t.isLive || t.streamUrl.isBlank()) continue
            if (t.contentKind != ContentKind.SERIES && t.contentKind != ContentKind.OTHER_VOD) continue
            if (matchScore(expected, ep.series) >= 0.80) return t
        }
        return null
    }

    // ---------------------------------------------------------------- publicar

    private fun publish(i: Int, entries: List<Entry>) {
        synchronized(lock) {
            if (entriesBySource[i] == entries) return
            entriesBySource[i] = entries
            rebuildPublishedState()
        }
    }

    /** Debe llamarse con `lock` tomado. */
    private fun rebuildPublishedState() {
        val flat = entriesBySource.entries.sortedBy { it.key }.flatMap { it.value }
        allEntries = flat
        resolvedCatalog.clear()

        val visibleCatalog = catalogCategories.toMutableList()
        if (searchCatalogTitles.isNotEmpty()) {
            visibleCatalog.add(Category("tmdb-search-results", "Descubiertos recientemente", searchCatalogTitles))
        }
        val secs = sources.indices.mapNotNull { n ->
            entriesBySource[n]?.takeUnless { sources[n].mergeIntoArgentina }?.let { buildSection(n, sources[n], it) }
        }.toMutableList()
        if (visibleCatalog.isNotEmpty()) secs.add(Section("tmdb-catalog", "Catálogo", "🎬", visibleCatalog, visibleCatalog.size))

        val ids = HashMap<String, Title>(flat.size + 512)
        val index = ArrayList<Pair<String, Title>>(flat.size + 512)
        for (entry in flat) {
            ids[entry.title.id] = entry.title
            index.add(entry.norm to entry.title)
        }
        for (category in visibleCatalog) {
            for (title in category.items) {
                if (ids.put(title.id, title) == null) index.add(normalizeText(title.name) to title)
            }
        }
        byId = ids
        searchIndex = index
        _sections.value = secs
    }

    override fun search(query: String, limit: Int): List<Title> {
        val tokens = normalizeText(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Title>()
        val seen = HashSet<String>()
        for ((name, title) in searchIndex) {
            if (tokens.all { name.contains(it) }) {
                val shown = resolveForDisplay(title)
                if (seen.add(shown.streamUrl.ifBlank { shown.id })) {
                    out.add(shown)
                    if (out.size >= limit) break
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ zapping

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
        val entries = sourceIndex?.let { idx -> synchronized(lock) { entriesBySource[idx] } }.orEmpty()
        val group = entries.firstOrNull { it.title.id == titleId }?.group
        val byPriority = compareBy<Entry>({ channelPriorityNorm(it.norm) }, { it.norm })
        val sameGroup = entries.filter { it.title.isLive && it.group == group }
        if (sameGroup.size > 1) return sameGroup.sortedWith(byPriority).map { it.title }
        val sameSource = entries.filter { it.title.isLive }
        return if (sameSource.isNotEmpty()) sameSource.sortedWith(byPriority).map { it.title } else getArgentinaChannels()
    }

    /** Busca el siguiente episodio reproducible de la misma serie si la fuente los etiqueta. */
    fun getNextEpisode(titleId: String): Title? {
        val current = byId[titleId] ?: return null
        if (current.isLive || current.streamUrl.isBlank()) return null
        val currentEpisode = episodeOf(current.name) ?: return null
        val sameSeries = allEntries.asSequence()
            .filter { !it.title.isLive && it.title.streamUrl.isNotBlank() && it.title.contentKind == current.contentKind }
            .mapNotNull { e -> e.episode?.takeIf { it.series.text == currentEpisode.series.text }?.let { e.title to it } }
            .distinctBy { it.first.id }
            .sortedWith(compareBy<Pair<Title, EpisodeInfo>>({ it.second.season }, { it.second.episode }))
            .toList()
        val index = sameSeries.indexOfFirst { it.first.id == titleId }
        if (index < 0) return null
        return sameSeries.getOrNull(index + 1)?.first
    }

    /** Un fallo de reproducción puede ser transitorio: no bloquea ni borra la fuente M3U. */
    override suspend fun markUnavailable(id: String) = Unit

    /** Cola de zapping idéntica a la fila Argentina: top 9, todos los GH, luego el resto. */
    fun getArgentinaChannels(): List<Title> {
        val row = _sections.value.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
            ?.categories.orEmpty().flatMap { it.items }
        return row.ifEmpty {
            allEntries.filter { it.title.isLive }
                .distinctBy { it.title.streamUrl }
                .sortedWith(compareBy<Entry>({ argentinaRankNorm(it.norm) }, { it.norm }))
                .map { it.title }
        }
    }

    /** Curated order: first nine familiar Argentine channels, then every Gran Hermano feed, then the rest. */
    private fun argentinaRankNorm(n: String): Int = when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") || "todo noticias" in n -> 0
        "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
        "cronica" in n -> 2
        "el trece" in n || "eltrece" in n -> 3
        "telefe" in n -> 4
        n == "america" || n.startsWith("america ") || "america tv" in n -> 5
        "c5n" in n -> 6
        n.contains("canal 9") || n == "el nueve" || n.startsWith("canal nueve") -> 7
        "tv publica" in n || "television publica" in n -> 8
        isGranHermanoNorm(n) -> 9
        else -> 10
    }

    private fun argentinaDedupeKey(entry: Entry): String {
        val n = entry.norm
        return when {
            isGranHermanoNorm(n) -> "gh:${entry.title.streamUrl}"
            n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") || "todo noticias" in n -> "tn"
            "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> "ln"
            "cronica" in n -> "cronica"
            "el trece" in n || "eltrece" in n -> "eltrece"
            "telefe" in n -> "telefe"
            n == "america" || n.startsWith("america ") || "america tv" in n -> "america"
            "c5n" in n -> "c5n"
            n.contains("canal 9") || n == "el nueve" || n.startsWith("canal nueve") -> "canal9"
            "tv publica" in n || "television publica" in n -> "tvpublica"
            else -> "url:${entry.title.streamUrl}"
        }
    }

    private fun channelPriorityNorm(n: String): Int = when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> 0
        "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
        "cronica" in n -> 2
        "eltrece" in n || "el trece" in n -> 3
        "telefe" in n -> 4
        "america" in n -> 5
        isGranHermanoNorm(n) -> 6
        else -> 100
    }

    private fun buildSection(i: Int, source: M3uSource, entries: List<Entry>): Section {
        if (source.flattenIntoOneCategory) {
            val isArgentina = source.name.equals("Argentina", ignoreCase = true)
            val mergedExtras = if (isArgentina) sources.indices
                .filter { sources[it].mergeIntoArgentina }
                .flatMap { entriesBySource[it].orEmpty() }
                .filter { it.title.isLive } else emptyList()
            val realityEntries = if (isArgentina) {
                entriesBySource.values.flatten().filter { it.title.isLive && isGranHermanoNorm(it.norm) }
            } else emptyList()
            val raw = entries + mergedExtras + realityEntries
            val deduped = if (isArgentina) raw.distinctBy(::argentinaDedupeKey) else raw.distinctBy { it.title.streamUrl }
            val sorted = deduped.sortedWith(
                compareBy<Entry>({ if (isArgentina) argentinaRankNorm(it.norm) else Int.MAX_VALUE }, { it.norm })
            )
            val category = Category("s$i-argentina", "Canales argentinos", sorted.map { it.title })
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

    private fun downloadWithRetry(url: String): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                return download(url)
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) Thread.sleep(350L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("No se pudo descargar la lista")
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 25_000
        conn.readTimeout = 45_000
        conn.useCaches = false
        if (conn is HttpURLConnection) {
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) XuperQuilmesTV")
            conn.setRequestProperty("Accept", "application/x-mpegURL, audio/mpegurl, text/plain, */*")
            val status = conn.responseCode
            if (status !in 200..299) {
                conn.disconnect()
                throw java.io.IOException("HTTP $status al descargar una lista")
            }
        }
        try {
            val bytes = conn.getInputStream().use { it.readBytes() }
            return decodePlaylist(bytes, conn.contentType)
        } finally {
            (conn as? HttpURLConnection)?.disconnect()
        }
    }

    /** Decodifica BOM/charset del servidor y cae de UTF-8 a Windows-1252/Latin-1 si hace falta. */
    private fun decodePlaylist(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val charsetName = Regex("""(?i)charset=([^;\s]+)""").find(contentType.orEmpty())?.groupValues?.get(1)?.trim('"', '\'')
        if (!charsetName.isNullOrBlank()) {
            runCatching { return String(bytes, Charset.forName(charsetName)) }
        }
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        // ISO-8859-1 decodifica cualquier byte; se usa como fallback para playlists antiguas.
        return if (utf8.contains('\uFFFD')) String(bytes, StandardCharsets.ISO_8859_1) else utf8
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
                        entryOf(
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
    private fun classifyContent(name: String, group: String, url: String): ContentKind {
        val n = normalizeText(name)
        val g = normalizeText(group)
        val path = normalizeText(runCatching { URL(url).path }.getOrDefault(url))
        val metadata = "$n $g $path"

        val seriesPath = SERIES_PATH_RE.containsMatchIn(path)
        val seriesWords = listOf("series vod", "tv series", "series on demand", "series a la carta", "episodios", "episodes")
        val seriesGroup = g in setOf("series", "serie", "series tv", "episodios", "episodes", "series completas", "tv shows", "temporadas")
        if (seriesPath || EPISODE_NAME_RE.containsMatchIn(n) || seriesGroup || seriesWords.any { it in metadata }) {
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

        val moviePath = MOVIE_PATH_RE.containsMatchIn(path)
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
        val attrs = ATTR.findAll(head).associate { match ->
            val value = (2..4).asSequence().map { match.groupValues[it] }.firstOrNull { it.isNotEmpty() }.orEmpty()
            match.groupValues[1].lowercase() to value
        }
        // Algunas listas omiten la coma y colocan el nombre en tvg-name o tvg-id.
        val fallbackName = attrs["tvg-name"].orEmpty().ifBlank { attrs["tvg-id"].orEmpty() }
        return attrs to name.ifBlank { fallbackName }
    }

    // ----------------------------------------------------------------- caché

    private fun cacheFile(i: Int): File {
        val sourceName = sources.getOrNull(i)?.name.orEmpty().lowercase()
            .replace(CACHE_NAME_RE, "_").trim('_').ifBlank { "source" }
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
                    entryOf(p[0], if (p.size >= 10) base.copy(
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

    private companion object {
        val ATTR = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        val LAST_GROUPS = setOf("undefined", "otros")
    }
}
