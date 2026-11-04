package com.quilmes.xuper.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * Pista externa observada en las solicitudes normales que realiza el embed.
 * No intenta fabricar enlaces ni resolver firmas: conserva la URL y el Referer del pedido.
 */
data class ExternalSubtitle(
    val url: String,
    val referer: String,
    val language: String = "und",
    val label: String = "Subtítulo del reproductor",
    val hearingImpaired: Boolean = false,
    val forced: Boolean = false,
) {
    val mimeType: String
        get() = when {
            url.substringBefore('?').substringBefore('#').endsWith(".vtt", true) ||
                Uri.parse(url).getQueryParameter("format")?.equals("vtt", true) == true -> "text/vtt"
            else -> "application/x-subrip"
        }

    companion object {
        private val subtitleUrl = Regex(
            """(?:\.(?:srt|vtt)(?:[?#]|$))|[?&]format=(?:srt|vtt)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun isSubtitleUrl(url: String): Boolean =
            url.startsWith("https://", true) && subtitleUrl.containsMatchIn(url)

        fun fromRequest(url: String, referer: String): ExternalSubtitle {
            val metadata = runCatching {
                val encoded = Uri.parse(url).getQueryParameter("m") ?: return@runCatching JSONObject()
                val normalized = encoded.replace('-', '+').replace('_', '/')
                val bytes = Base64.decode(normalized, Base64.DEFAULT or Base64.URL_SAFE)
                JSONObject(bytes.toString(Charsets.UTF_8))
            }.getOrDefault(JSONObject())
            val lang = metadata.optString("lang", "und").lowercase().take(5).ifBlank { "und" }
            val fallback = when {
                lang.startsWith("es") -> "Español"
                lang.startsWith("en") -> "English"
                lang == "und" -> "Subtítulo del reproductor"
                else -> lang
            }
            return ExternalSubtitle(
                url = url,
                referer = referer.ifBlank { "https://cinesrc.st/" },
                language = lang,
                label = metadata.optString("display").takeIf { it.isNotBlank() } ?: fallback,
                hearingImpaired = metadata.optString("hi") == "1",
                forced = metadata.optString("forced") == "1",
            )
        }
    }
}
