package com.quilmes.xuper.data

import org.json.JSONObject
import java.text.Normalizer

/** Comparación de títulos entre servicios (TMDb / AniList). */
internal object TitleMatch {
    fun norm(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** 1.0 = mismo título normalizado; si no, proporción de palabras en común (0..1). */
    fun similarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tb = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }
}

/** org.json devuelve el texto "null" para un valor JSON null; esto devuelve "" en ese caso. */
internal fun JSONObject?.strOrEmpty(name: String): String =
    if (this == null || isNull(name)) "" else optString(name)
