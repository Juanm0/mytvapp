package com.quilmes.xuper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Categorías del catálogo +18. `order` es el parámetro `o=` de la página de listado (el mismo mapa que usa pornhub.js). */
enum class AdultCategory(val label: String, val order: String) {
    POPULAR("Populares", "ht"),
    RECENT("Recientes", "cm"),
    MOST_VIEWED("Más vistos", "mv"),
    TOP_RATED("Mejor valorados", "tr"),
    LONGEST("Más largos", "lg"),
}

data class AdultVideo(
    val viewKey: String,
    val title: String,
    val thumbUrl: String,
    val duration: String,
    /** Texto tal cual lo muestra el sitio ("1.2M"); vacío si no está disponible. */
    val views: String,
    val hd: Boolean,
)

data class AdultPage(val videos: List<AdultVideo>, val page: Int, val hasNext: Boolean)

/**
 * Catálogo +18 dinámico. NO hay lista estática: cada pantalla sale de una consulta en vivo.
 *
 * pornhub.js es una biblioteca de Node.js y no puede correr dentro de la app Android. En vez de montar un
 * backend aparte, este servicio hace en Kotlin (con Jsoup, que el proyecto ya usa) lo mismo que pornhub.js
 * (v1.15.0, `videoList` y `searchVideo`): pide la misma página y lee los mismos elementos (`li.videoblock`,
 * `.linkVideoThumb`, `.duration`, `.views var`, `li.page_next`). Solo obtiene METADATOS (título, miniatura,
 * duración, vistas y el `viewkey`): el video lo reproduce el embed oficial (`embedUrl`). No extrae M3U8/MP4.
 *
 * Solo funciona con el perfil +18 desbloqueado (AdultContentSettings).
 */
object AdultCatalogService {
    private const val BASE_URL = "https://www.pornhub.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    private val VIEWKEY_IN_URL = Regex("viewkey=([A-Za-z0-9]{1,30})")
    private val VIEWKEY_VALID = Regex("^[A-Za-z0-9]{5,30}$")

    fun isValidViewKey(viewKey: String): Boolean = VIEWKEY_VALID.matches(viewKey)

    
    suspend fun list(category: AdultCategory, page: Int): AdultPage =
        fetch(
            url = "$BASE_URL/video?o=${category.order}" + if (page > 1) "&page=$page" else "",
            containers = listOf("#videoCategory", ".videoUList"),
            page = page,
        )

    suspend fun search(query: String, page: Int): AdultPage {
        val keyword = searchify(query)
        if (keyword.isBlank()) return AdultPage(emptyList(), 1, false)
        return fetch(
            url = "$BASE_URL/video/search?search=${URLEncoder.encode(keyword, "UTF-8")}" + if (page > 1) "&page=$page" else "",
            containers = listOf("#videoSearchResult", ".videoUList"),
            page = page,
        )
    }

    private suspend fun fetch(url: String, containers: List<String>, page: Int): AdultPage = withContext(Dispatchers.IO) {
        check(AdultContentSettings.isEnabled()) { "El perfil +18 está bloqueado." }
        val doc = Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .cookie("accessAgeDisclaimerPH", "1")
            .cookie("accessAgeDisclaimerUK", "1")
            .cookie("platform", "pc")
            .timeout(15_000)
            .get()
        val videos = parseVideos(doc, containers)
        AdultPage(videos = videos, page = parseCurrentPage(doc, page), hasNext = hasNextPage(doc) && videos.isNotEmpty())
    }

    private fun parseVideos(doc: Document, containers: List<String>): List<AdultVideo> {
        var items = org.jsoup.select.Elements()
        for (container in containers) {
            items = doc.select("$container li.videoblock")
            if (items.isNotEmpty()) break
        }
        if (items.isEmpty()) items = doc.select("li.videoblock")
        val seen = HashSet<String>()
        val out = ArrayList<AdultVideo>(items.size)
        for (item in items) {
            val thumb = item.selectFirst(".linkVideoThumb") ?: continue
            val href = thumb.attr("href")
            if (href.startsWith("javascript")) continue
            // El embed solo reproduce lo gratuito: se saltan los videos exclusivos de Premium.
            if (item.selectFirst(".premiumIcon") != null && item.selectFirst(".marker-overlays .phpFreeBlock") == null) continue
            val viewKey = item.attr("data-video-vkey").ifBlank { VIEWKEY_IN_URL.find(href)?.groupValues?.get(1).orEmpty() }
            if (!isValidViewKey(viewKey) || !seen.add(viewKey)) continue
            out.add(
                AdultVideo(
                    viewKey = viewKey,
                    title = thumb.attr("title").ifBlank { item.selectFirst(".title a")?.text().orEmpty() }.trim().ifBlank { "Video" },
                    thumbUrl = thumbnailOf(item),
                    duration = item.selectFirst(".duration")?.text().orEmpty().trim(),
                    views = item.selectFirst(".views var")?.text().orEmpty().trim(),
                    hd = item.selectFirst(".hd-thumbnail") != null,
                )
            )
        }
        return out
    }

    private fun thumbnailOf(item: Element): String {
        val img = item.selectFirst("img") ?: return ""
        // El sitio carga las miniaturas de forma diferida: el `src` puede ser un marcador de posición.
        for (attr in listOf("data-mediumthumb", "data-src", "data-thumb_url", "src")) {
            val value = img.attr(attr).trim()
            if (value.startsWith("http")) return value
        }
        return ""
    }

    private fun parseCurrentPage(doc: Document, fallback: Int): Int =
        doc.selectFirst("li.page_current")?.text()?.trim()?.toIntOrNull() ?: fallback

    private fun hasNextPage(doc: Document): Boolean {
        val next = doc.selectFirst("li.page_next") ?: return false
        return !next.hasClass("disabled")
    }

    /** Misma normalización de la búsqueda que pornhub.js (`searchify`). */
    private fun searchify(keyword: String): String =
        keyword.trim()
            .replace(Regex("[.,'/:=()&!?@\\[\\]\"*$#%^;|`\\\\~><¿{}+]"), " ")
            .replace(Regex("[éèëê]", RegexOption.IGNORE_CASE), "e")
            .replace(Regex("[äàâ]", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("[üùû]", RegexOption.IGNORE_CASE), "u")
            .replace(Regex("[îï]", RegexOption.IGNORE_CASE), "i")
            .replace(Regex("[ôó]", RegexOption.IGNORE_CASE), "o")
            .replace(Regex("ç", RegexOption.IGNORE_CASE), "c")
            .trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
}
