package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class JkanimeCard(
    val title: String,
    val url: String,
    val slug: String,
    val image: String = "",
    val description: String = "",
)

data class JkanimeAnime(
    val title: String,
    val url: String,
    val slug: String,
    val id: String,
    val image: String = "",
    val description: String = "",
)

data class JkanimeEpisode(
    val id: String,
    val number: Int,
    val title: String,
    val image: String = "",
    val timestamp: String = "",
    val url: String,
)

data class JkanimeEpisodePage(
    val anime: JkanimeAnime,
    val page: Int,
    val total: Int,
    val totalPages: Int,
    val episodes: List<JkanimeEpisode>,
)

data class JkanimeStream(
    val episodeUrl: String,
    val playerUrl: String,
    val streamUrl: String,
)

/**
 * Port Android/Kotlin de las funciones principales de jkanime-anime-hub 2.3.
 *
 * Las peticiones se ejecutan en Dispatchers.IO. Los resultados del catálogo y las fichas
 * se parsean en memoria; no se escribe contenido remoto en el almacenamiento de la app.
 */
class JkanimeService(baseUrl: String = "https://jkanime.net") {
    /** URL base del sitio SIN barra final. Cada instancia trabaja con un solo sitio. */
    val base: String = baseUrl.trim().trimEnd('/').also {
        require(it.startsWith("https://") || it.startsWith("http://")) { "La URL del sitio debe empezar con https://" }
    }
    private val siteHost: String = (java.net.URI(base).host ?: error("URL de sitio inválida: $base")).lowercase(Locale.ROOT)
    /** Nombre corto del sitio ("jkanime", "jkcarandmore"…) para limpiar títulos como "Anime - JKanime". */
    private val siteName: String = Regex.escape(siteHost.removePrefix("www.").substringBeforeLast('.'))
    private fun isSiteHost(host: String?): Boolean =
        host != null && (host.equals(siteHost, true) || host.endsWith(".$siteHost", true))
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val metaCache = ConcurrentHashMap<String, CachedMeta>()
    private val sessionCache = ConcurrentHashMap<String, AnimeSession>()
    private val metaTtlMs = 30 * 60 * 1000L

    private data class CachedMeta(
        val value: JkanimeCard,
        val expiresAt: Long,
    )

    private data class AnimeSession(
        val anime: JkanimeAnime,
        val token: String,
        val cookies: Map<String, String>,
    )

    suspend fun loadCatalog(limit: Int = 48): List<JkanimeCard> = withContext(Dispatchers.IO) {
        val html = fetch("$base/").body
        val cards = extractCards(html, limit)
        hydrateCards(cards, onlyIncomplete = true)
    }

    suspend fun searchAnime(query: String, limit: Int = 48): List<JkanimeCard> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isBlank()) return@withContext emptyList()
        val keys = listOf("q", "texto", "search", "query", "anime", "nombre", "term", "keyword", "buscar")
        var lastError: Throwable? = null
        var successfulResponses = 0
        for (path in listOf("/buscar", "/ajax_search")) {
            for (key in keys) {
                val url = "$base$path?$key=${urlEncode(term)}"
                try {
                    val response = fetch(url, mapOf(
                        "Referer" to "$base/",
                        "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
                        "X-Requested-With" to if (path == "/ajax_search") "XMLHttpRequest" else "XMLHttpRequest",
                    ))
                    successfulResponses++
                    val finalUri = runCatching { java.net.URI(response.finalUrl) }.getOrNull()
                    if (finalUri?.path == "/" && finalUri.rawQuery.isNullOrBlank()) continue
                    val found = extractCards(response.body, limit)
                    val terms = term.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
                    val relevant = found.filter { card ->
                        val haystack = "${card.title} ${card.slug}".lowercase(Locale.ROOT).replace('-', ' ')
                        terms.any { word -> haystack.contains(word) }
                    }
                    if (relevant.isNotEmpty()) return@withContext hydrateCards(relevant.take(limit), onlyIncomplete = false)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }
        if (successfulResponses == 0 && lastError != null) {
            throw IllegalStateException("No se pudo consultar el buscador de JKanime: ${lastError.message}", lastError)
        }
        emptyList()
    }

    suspend fun getAnimeInfo(animeUrl: String): JkanimeAnime = withContext(Dispatchers.IO) {
        val session = getSession(animeUrl)
        session.anime
    }

    suspend fun getEpisodesPage(animeUrl: String, pageNumber: Int = 1): JkanimeEpisodePage =
        withContext(Dispatchers.IO) {
            require(pageNumber >= 1) { "El número de página debe ser positivo." }
            val session = getSession(animeUrl)
            val endpoint = "$base/ajax/episodes/${session.anime.id}/$pageNumber"
            val response = Jsoup.connect(endpoint)
                .userAgent(userAgent)
                .timeout(20_000)
                .ignoreContentType(true)
                .method(Connection.Method.POST)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-CSRF-TOKEN", session.token)
                .header("Referer", session.anime.url)
                .header("Origin", base)
                .cookies(session.cookies)
                .data("_token", session.token)
                .execute()
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("JKanime respondió HTTP ${response.statusCode()} al cargar episodios.")
            }
            val json = JSONObject(response.body())
            val data = json.optJSONArray("data")
                ?: json.optJSONObject("data")?.optJSONArray("data")
            val total = json.optInt("total", json.optJSONObject("data")?.optInt("total", 0) ?: 0)
            val episodes = buildList {
                if (data != null) for (i in 0 until data.length()) {
                    val ep = data.optJSONObject(i) ?: continue
                    val number = ep.optString("number").toIntOrNull() ?: continue
                    add(
                        JkanimeEpisode(
                            id = ep.optString("id", number.toString()),
                            number = number,
                            title = ep.optString("title").takeIf(String::isNotBlank) ?: "Episodio $number",
                            image = absoluteUrl(ep.optString("image")),
                            timestamp = ep.optString("timestamp"),
                            url = "$base/${session.anime.slug}/$number/",
                        )
                    )
                }
            }.sortedBy { it.number }
            JkanimeEpisodePage(
                anime = session.anime,
                page = pageNumber,
                total = total,
                totalPages = if (total > 0) ((total + 15) / 16).coerceAtLeast(1) else if (episodes.isEmpty()) pageNumber else pageNumber + if (episodes.size >= 16) 1 else 0,
                episodes = episodes,
            )
        }

    suspend fun getEpisodeStream(episodeUrl: String): JkanimeStream = withContext(Dispatchers.IO) {
        val parsed = java.net.URI(episodeUrl)
        require(isSiteHost(parsed.host)) {
            "La URL del episodio debe pertenecer a $siteHost."
        }
        val episode = fetch(episodeUrl)
        val episodeDoc = Jsoup.parse(episode.body, episode.finalUrl)
        val playerUrl = Regex("""(?:https?:)?\\?/\\?/${Regex.escape(siteHost)}/jkplayer/um\?[^"'\s<>]+|/jkplayer/um\?[^"'\s<>]+""", RegexOption.IGNORE_CASE)
            .find(episode.body)?.value?.replace("\\/", "/")
            ?.let { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "$base$it" else it }
            ?: episodeDoc.select("iframe[src], a[href]").mapNotNull { element ->
                (element.attr("src").ifBlank { element.attr("href") }).takeIf { it.contains("/jkplayer/um?") }
                    ?.let(::absoluteUrl)
            }.firstOrNull()
            ?: throw IllegalStateException("No encontré el reproductor dentro de la página del episodio.")

        val playerResponse = fetch(playerUrl, mapOf("Referer" to episodeUrl))
        val html = playerResponse.body
        val patterns = listOf(
            Regex("""hls\.loadSource\(\s*["']([^"']+\.m3u8[^"']*)["']\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""loadSource\(\s*["']([^"']+\.m3u8[^"']*)["']\s*\)""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?:\\?/\\?/[^"']+\.m3u8(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE),
        )
        val stream = patterns.firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.let(::absoluteUrl)
        } ?: throw IllegalStateException("No encontré un enlace HLS (.m3u8) en el reproductor.")
        JkanimeStream(episodeUrl = episodeUrl, playerUrl = playerUrl, streamUrl = stream)
    }

    private fun getSession(animeUrl: String): AnimeSession {
        val normalized = normalizeAnimeUrl(animeUrl)
        sessionCache[normalized]?.let { return it }
        val response = fetch(normalized)
        val doc = Jsoup.parse(response.body, response.finalUrl)
        val slug = slugFromUrl(normalized)
        val ids = Regex("""ajax/(?:episodes|search_episode)/(\d+)""")
            .findAll(response.body).map { it.groupValues[1] }.distinct().toList()
        if (ids.isEmpty()) throw IllegalStateException("No pude detectar el ID interno de JKanime en la ficha.")
        if (ids.size > 1) throw IllegalStateException("La ficha contiene varios IDs posibles: ${ids.joinToString()}")
        val token = doc.selectFirst("meta[name=csrf-token]")?.attr("content").orEmpty()
            .ifBlank { Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']""", RegexOption.IGNORE_CASE).find(response.body)?.groupValues?.get(1).orEmpty() }
        if (token.isBlank()) throw IllegalStateException("No encontré el token CSRF en la ficha.")
        val meta = extractMeta(doc, normalized, slug)
        val anime = JkanimeAnime(
            title = meta.title,
            url = normalized,
            slug = slug,
            id = ids.single(),
            image = meta.image,
            description = meta.description,
        )
        val session = AnimeSession(anime, token, response.cookies)
        sessionCache[normalized] = session
        return session
    }

    private suspend fun hydrateCards(cards: List<JkanimeCard>, onlyIncomplete: Boolean): List<JkanimeCard> =
        coroutineScope {
            val gate = Semaphore(4)
            cards.map { card ->
                async(Dispatchers.IO) {
                    if (onlyIncomplete && card.image.isNotBlank() && !isSuspiciousTitle(card.title)) return@async card
                    gate.withPermit {
                        try {
                            val cached = metaCache[card.url]
                            val now = System.currentTimeMillis()
                            val meta = if (cached != null && cached.expiresAt > now) cached.value else {
                                val response = fetch(card.url)
                                val extracted = extractMeta(Jsoup.parse(response.body, response.finalUrl), response.finalUrl, card.slug)
                                val value = card.copy(
                                    title = if (extracted.title.isNotBlank()) extracted.title else card.title,
                                    image = extracted.image.ifBlank { card.image },
                                    description = extracted.description,
                                )
                                metaCache[card.url] = CachedMeta(value, now + metaTtlMs)
                                value
                            }
                            card.copy(
                                title = meta.title.takeIf(String::isNotBlank) ?: card.title,
                                image = meta.image.ifBlank { card.image },
                                description = meta.description,
                            )
                        } catch (_: Throwable) {
                            card.copy(title = card.title.ifBlank { humanizeSlug(card.slug) })
                        }
                    }
                }
            }.awaitAll()
        }

    private data class Meta(val title: String, val image: String, val description: String)

    private fun extractMeta(doc: Document, pageUrl: String, slug: String): Meta {
        fun meta(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            doc.selectFirst("meta[property='$key'], meta[name='$key']")?.attr("content")?.takeIf(String::isNotBlank)
        }.orEmpty()
        val candidates = listOf(meta("og:title"), meta("twitter:title"), doc.selectFirst("h1")?.text().orEmpty(), doc.title())
        val title = candidates.map(::cleanText).map(::stripSiteName).firstOrNull { !isSuspiciousTitle(it) }
            ?: humanizeSlug(slug).ifBlank { "Anime" }
        val image = listOf(
            meta("og:image"), meta("og:image:secure_url"), meta("twitter:image"), meta("twitter:image:src"),
            doc.selectFirst("link[rel~=image_src]")?.attr("href").orEmpty(),
        ).mapNotNull { absoluteUrl(it).takeIf(String::isNotBlank) }.firstOrNull().orEmpty()
        return Meta(title, image, cleanText(meta("og:description", "description")))
    }

    private data class FetchResult(
        val body: String,
        val finalUrl: String,
        val cookies: Map<String, String> = emptyMap(),
    )

    private fun fetch(url: String, extraHeaders: Map<String, String> = emptyMap()): FetchResult {
        val response = Jsoup.connect(url)
            .userAgent(userAgent)
            .timeout(20_000)
            .followRedirects(true)
            .ignoreHttpErrors(false)
            .header("Accept-Language", "es-AR,es;q=0.9,en;q=0.8")
            .headers(extraHeaders)
            .execute()
        return FetchResult(response.body(), response.url().toString(), response.cookies())
    }

    private fun extractCards(rawHtml: String, limit: Int): List<JkanimeCard> {
        val found = linkedMapOf<String, JkanimeCard>()
        val sources = mutableListOf(rawHtml)

        fun addCard(rawUrl: String, rawTitle: String, rawImage: String = "") {
            val url = absoluteUrl(rawUrl)
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return
            val host = uri.host.orEmpty().lowercase(Locale.ROOT)
            if (!isSiteHost(host)) return
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            if (segments.size != 1) return
            val slug = segments[0]
            if (slug.lowercase(Locale.ROOT) in setOf(
                    "buscar", "generos", "anime", "login", "register", "ajax", "assets", "contacto",
                    "dmca", "directorio", "horario", "comunidad", "top", "estrenos", "temporada"
                )) return
            if (slug.contains('.') && slug.substringAfterLast('.').lowercase() in setOf("css", "js", "png", "jpg", "jpeg", "webp", "svg", "ico")) return
            val cleanTitle = cleanText(rawTitle).takeUnless(::isSuspiciousTitle) ?: humanizeSlug(slug)
            val card = JkanimeCard(cleanTitle, url, slug, absoluteUrl(rawImage))
            val old = found[url]
            found[url] = if (old == null) card else old.copy(
                title = if (isSuspiciousTitle(old.title) && !isSuspiciousTitle(card.title)) card.title else old.title,
                image = old.image.ifBlank { card.image },
            )
        }

        fun collectJson(value: Any?, depth: Int) {
            if (depth > 8 || value == null || value == JSONObject.NULL) return
            when (value) {
                is JSONObject -> {
                    val title = listOf("title", "name", "nombre", "anime")
                        .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
                    val href = listOf("url", "href", "link", "permalink")
                        .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
                    val image = listOf("image", "poster", "cover", "thumbnail")
                        .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() && it != "null" } }.orEmpty()
                    if (title.isNotBlank() && href.isNotBlank()) addCard(href, title, image)
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val child = value.opt(keys.next())
                        if (child is String && (child.contains("<a", true) || child.contains("$siteHost/"))) sources += child
                        else collectJson(child, depth + 1)
                    }
                }
                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) collectJson(value.opt(i), depth + 1)
                }
                is String -> {
                    val text = value.trim()
                    if ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]"))) {
                        runCatching {
                            if (text.startsWith("{")) collectJson(JSONObject(text), depth + 1)
                            else collectJson(org.json.JSONArray(text), depth + 1)
                        }
                    } else if (text.contains("<a", true) || text.contains("$siteHost/")) {
                        sources += text
                    }
                }
            }
        }

        val trimmed = rawHtml.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching {
                if (trimmed.startsWith("{")) collectJson(JSONObject(trimmed), 0)
                else collectJson(org.json.JSONArray(trimmed), 0)
            }
        }

        for (source in sources.distinct()) {
            val doc = Jsoup.parse(source, base)
            for (anchor in doc.select("a[href]")) {
                val imageTag = anchor.selectFirst("img, source")
                val image = imageTag?.let { img ->
                    img.attr("src").ifBlank {
                        img.attr("data-src").ifBlank { img.attr("data-lazy-src").ifBlank { img.attr("data-original") } }
                    }
                }.orEmpty()
                val title = imageTag?.let { it.attr("alt").ifBlank { it.attr("title") } }
                    .orEmpty().ifBlank { anchor.attr("title") }.ifBlank { anchor.text() }
                addCard(anchor.attr("href"), title, image)
                if (found.size >= limit) return found.values.take(limit)
            }
        }
        return found.values.take(limit)
    }

    private fun normalizeAnimeUrl(raw: String): String {
        val uri = java.net.URI(raw.trim())
        require(uri.scheme in listOf("https", "http") && isSiteHost(uri.host)) {
            "Usá una ficha de $siteHost."
        }
        return "$base/${slugFromUrl(raw)}/"
    }

    private fun slugFromUrl(raw: String): String {
        val path = java.net.URI(raw).path.orEmpty().split('/').filter(String::isNotBlank)
        require(path.isNotEmpty()) { "La URL no contiene el slug del anime." }
        return path.first()
    }

    private fun absoluteUrl(raw: String): String {
        val value = cleanText(raw).trim()
        if (value.isBlank() || value.startsWith("data:", true)) return ""
        return runCatching { java.net.URI(base).resolve(value).toString().takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: "" }.getOrDefault("")
    }

    private fun cleanText(value: String): String = Jsoup.parse(value).text().replace(Regex("\\s+"), " ").trim()

    private fun humanizeSlug(slug: String): String =
        slug.replace(Regex("[-_]+"), " ").trim().replace(Regex("(^|\\s)(\\p{L})")) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase(Locale.ROOT)
        }

    private fun stripSiteName(value: String): String =
        value.replace(Regex("\\s*[-–—|·•:]\\s*(?:jkanime|$siteName)(?:\\.\\w+)?\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*(?:jkanime|$siteName)(?:\\.\\w+)?\\s*[-–—|·•:]\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun isSuspiciousTitle(value: String): Boolean {
        val t = value.trim()
        return t.isBlank() || t.length > 180 || t.matches(Regex("\\d+")) ||
            Regex("\\d+\\s*/\\s*\\d+").containsMatchIn(t) || t.contains("-->") || t.contains("<--") ||
            t.startsWith("<") || t.startsWith(">") ||
            t.matches(Regex("(?i)(siguiente|anterior|next|prev(?:ious)?|ver ficha|ver m[aá]s|(?:jkanime|$siteName)(?:\\.\\w+)?)"))
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
