package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

internal enum class LibraryMode { ARGENTINA, FAVORITES, CONTINUE }

@Composable
internal fun LibraryScreen(sections: List<Section>, mode: LibraryMode, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("xuper-library", Context.MODE_PRIVATE) }
    val favoriteIds = remember(sections, mode) { prefs.getStringSet("favorites", emptySet())?.toSet().orEmpty() }
    val recentOrder = remember(sections, mode) { prefs.getString("recent", "").orEmpty().split("|").filter { it.isNotBlank() }.asReversed() }
    val recentIds = recentOrder.toSet()
    val savedRecentTitles = remember(sections, mode, prefs.getString("recent_items", "[]")) {
        runCatching {
            val json = org.json.JSONArray(prefs.getString("recent_items", "[]"))
            (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = o.optString("streamUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = runCatching { com.quilmes.xuper.data.ContentKind.valueOf(o.optString("contentKind")) }.getOrDefault(com.quilmes.xuper.data.ContentKind.OTHER_VOD)
                Title(id=id, name=o.optString("name", "Contenido"), description=o.optString("description", ""),
                    posterUrl=o.optString("posterUrl", ""), backdropUrl=o.optString("backdropUrl", o.optString("posterUrl", "")),
                    streamUrl=url, isLive=o.optBoolean("isLive", false), year=o.optString("year").takeIf { it.isNotBlank() },
                    rating=o.optString("rating").takeIf { it.isNotBlank() }, contentKind=kind)
            }
        }.getOrDefault(emptyList())
    }
    // Igual que en HomeScreen: este cálculo recorre todas las listas cargadas
    // (puede haber miles de entradas entre Argentina + VOD) y antes corría
    // sincrónicamente en el hilo principal cada vez que se abría esta pestaña,
    // congelando la navegación. Ahora se calcula en segundo plano.
    var rows by remember { mutableStateOf<List<Category>>(emptyList()) }
    LaunchedEffect(sections, mode, favoriteIds, recentIds) {
        val computed = withContext(Dispatchers.Default) {
            buildLibraryRows(sections, mode, favoriteIds, recentIds, recentOrder)
        }
        rows = computed
    }
    val heading = when (mode) {
        LibraryMode.ARGENTINA -> "ARGENTINA"
        LibraryMode.FAVORITES -> "MI LISTA"
        LibraryMode.CONTINUE -> "CONTINUAR VIENDO"
    }
    val emptyTitle = when (mode) {
        LibraryMode.ARGENTINA -> "No hay canales argentinos disponibles"
        LibraryMode.FAVORITES -> "Todavía no guardaste favoritos"
        LibraryMode.CONTINUE -> "Todavía no hay contenido para continuar"
    }
    val emptyMessage = when (mode) {
        LibraryMode.ARGENTINA -> "La lista argentina no devolvió canales. Revisá la conexión o la fuente M3U."
        LibraryMode.FAVORITES -> "Abrí una ficha y tocá Mi lista para guardar una película o serie."
        LibraryMode.CONTINUE -> "El contenido reproducible que abras recientemente aparecerá acá."
    }

    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(horizontal = 48.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.foundation.layout.Column {
                Text(emptyTitle, color = XuperTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(emptyMessage, color = XuperTextSecondary, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)) {
            item {
                Text(heading, color = XuperTextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp))
            }
            items(rows, key = { it.id }) { category ->
                ContentRow(category = category, onItemClick = onPlay, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

private fun buildLibraryRows(
    sections: List<Section>,
    mode: LibraryMode,
    favoriteIds: Set<String>,
    recentIds: Set<String>,
    recentOrder: List<String>,
): List<Category> {
    val allTitles by lazy { sections.flatMap { it.categories }.flatMap { it.items }.distinctBy { it.id } }
    return when (mode) {
        LibraryMode.FAVORITES -> listOfNotNull(
            allTitles.filter { it.id in favoriteIds }.takeIf { it.isNotEmpty() }
                ?.let { Category("favorites", "Mi lista", it) }
        )
        LibraryMode.CONTINUE -> listOfNotNull(
            (recentOrder.mapNotNull { id -> allTitles.firstOrNull { it.id == id && !it.isLive } ?: savedRecentTitles.firstOrNull { it.id == id && !it.isLive } }
                .distinctBy { it.id })
                .takeIf { it.isNotEmpty() }?.let { Category("continue", "Continuar viendo", it) }
        )
        LibraryMode.ARGENTINA -> {
            val argentina = sections.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
                ?.categories.orEmpty().flatMap { it.items }.filter { it.isLive }
            // Se normaliza el nombre UNA sola vez por canal (antes se hacía dentro del ordenamiento,
            // con una Regex nueva en cada comparación, y trababa la pestaña Argentina).
            val keyed = argentina.distinctBy { it.streamUrl }.map { it to normalizeName(it.name) }
            val orderedPairs = keyed.distinctBy { (title, n) ->
                if (n.contains("gran hermano") || n == "gh") "gh:${title.streamUrl}"
                else argentinaBrand(n) ?: "url:${title.streamUrl}"
            }.sortedWith(compareBy<Pair<Title, String>>({ argentinaPriority(it.second) }, { it.second }))
            val gh = orderedPairs.filter { (_, n) -> n.contains("gran hermano") || n == "gh" }.map { it.first }
            val sports = orderedPairs.filter { isArgentinaSport(it.second) }.map { it.first }
            val ghUrls = gh.map { it.streamUrl }.toSet()
            val sportUrls = sports.map { it.streamUrl }.toSet()
            val channels = orderedPairs.map { it.first }.filter { it.streamUrl !in ghUrls && it.streamUrl !in sportUrls }
            listOfNotNull(
                channels.takeIf { it.isNotEmpty() }?.let { Category("argentina-live", "Canales argentinos", it) },
                gh.takeIf { it.isNotEmpty() }?.let { Category("argentina-gh", "Gran Hermano", it) },
                sports.takeIf { it.isNotEmpty() }?.let { Category("argentina-sports", "Deportes Argentina", it) },
            )
        }
    }
}

private val MARKS_REGEX = Regex("\\p{Mn}+")

private fun normalizeName(value: String): String = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD).replace(MARKS_REGEX, "")

/** `n` ya viene normalizado con normalizeName. */
private fun argentinaBrand(n: String): String? {
    return when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> "tn"
        "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> "la nacion"
        "cronica" in n -> "cronica"
        "el trece" in n || "eltrece" in n -> "el trece"
        "telefe" in n -> "telefe"
        n == "america" || n.startsWith("america ") || "america tv" in n -> "america"
        "c5n" in n -> "c5n"
        "canal 9" in n || n == "el nueve" || n.startsWith("canal nueve") -> "canal 9"
        "tv publica" in n || "television publica" in n -> "tv publica"
        else -> null
    }
}

private fun argentinaPriority(n: String): Int {
    return when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> 0
        "la nacion" in n || "lanacion" in n || "ln+" in n || n == "ln" -> 1
        "cronica" in n -> 2
        "el trece" in n || "eltrece" in n -> 3
        "telefe" in n -> 4
        n == "america" || n.startsWith("america ") || "america tv" in n -> 5
        "c5n" in n -> 6
        "canal 9" in n || n == "el nueve" || n.startsWith("canal nueve") -> 7
        "tv publica" in n || "television publica" in n -> 8
        "gran hermano" in n || n == "gh" -> 9
        else -> 10
    }
}

private fun isArgentinaSport(n: String): Boolean {
    return listOf("espn", "fox sports", "tyc sports", "t y c sports", "directv sports", "dsports", "tnt sports", "deportv", "goltv", "sport tv").any { it in n }
}
