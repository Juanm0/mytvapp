package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import androidx.tv.material3.Text
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.CardWidth
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.components.SkeletonRows
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

internal enum class LibraryMode { ARGENTINA, OTHER_COUNTRIES, MOVIES, SERIES, ANIMATION }

@Composable
internal fun LibraryScreen(
    sections: List<Section>,
    mode: LibraryMode,
    onPlay: (String) -> Unit,
) {
    val rows = remember(sections, mode) {
        when (mode) {
            LibraryMode.ARGENTINA -> {
                val argentina = sections.firstOrNull { it.name.equals("Argentina", ignoreCase = true) }
                    ?.categories.orEmpty().flatMap { it.items }.filter { it.isLive }
                val gh = sections.flatMap { it.categories }.flatMap { it.items }
                    .filter { it.isLive && it.name.contains("gran hermano", ignoreCase = true) }
                val ordered = (argentina + gh).distinctBy { it.streamUrl }
                    .distinctBy { t -> argentinaBrand(t.name) ?: "url:${t.streamUrl}" }
                    .sortedWith(
                        compareBy<Title>(
                            { t -> argentinaPriority(t.name) },
                            { normalizeName(it.name) },
                        )
                    )
                if (ordered.isEmpty()) emptyList() else listOf(Category("argentina-live", "Argentina · Canales principales", ordered))
            }
            LibraryMode.OTHER_COUNTRIES -> sections.filterNot { it.name.equals("Argentina", ignoreCase = true) }
                .flatMap { section -> section.categories.mapNotNull { category ->
                    val filtered = category.items.filter { it.isLive && !it.name.contains("gran hermano", ignoreCase = true) }
                    if (filtered.isEmpty()) null else Category("countries-${category.id}", "${section.name} · ${category.title}", filtered)
                } }
            else -> sections.flatMap { section ->
                section.categories.mapNotNull { category ->
                    val filtered = category.items.filter { title ->
                        when (mode) {
                            LibraryMode.MOVIES -> title.contentKind == ContentKind.MOVIE || title.contentKind == ContentKind.OTHER_VOD
                            LibraryMode.SERIES -> title.contentKind == ContentKind.SERIES
                            LibraryMode.ANIMATION -> isAnimation(title)
                            else -> false
                        }
                    }
                    if (filtered.isEmpty()) null else Category("${mode.name}-${category.id}", category.title, filtered)
                }
            }
        }
    }

    val heading = when (mode) {
        LibraryMode.ARGENTINA -> "ARGENTINA"
        LibraryMode.OTHER_COUNTRIES -> "OTROS PAÍSES"
        LibraryMode.MOVIES -> "PELÍCULAS"
        LibraryMode.SERIES -> "SERIES"
        LibraryMode.ANIMATION -> "ANIMACIÓN"
    }

    if (rows.isEmpty()) {
        if (sections.isEmpty()) {
            SkeletonRows()
        } else {
            EmptyMessage(
                title = when (mode) {
                    LibraryMode.ARGENTINA -> "No hay canales argentinos disponibles"
                    LibraryMode.OTHER_COUNTRIES -> "No hay canales internacionales disponibles"
                    LibraryMode.MOVIES -> "Todavía no hay películas"
                    LibraryMode.SERIES -> "Todavía no hay series"
                    LibraryMode.ANIMATION -> "No encontramos canales de animación"
                },
                message = when (mode) {
                    LibraryMode.ARGENTINA -> "La lista de Argentina no devolvió canales reproducibles. Comprobá la conexión o actualizá la fuente."
                    LibraryMode.OTHER_COUNTRIES -> "Las listas internacionales no devolvieron canales reproducibles."
                    LibraryMode.MOVIES -> "Todavía no encontramos películas bajo demanda. Una lista de canales de cine en vivo no alcanza: la fuente tiene que incluir enlaces directos a películas."
                    LibraryMode.SERIES -> "Todavía no encontramos series o episodios bajo demanda. Agregá una fuente VOD autorizada con episodios y metadatos."
                    LibraryMode.ANIMATION -> "Cuando una lista incluya canales o títulos de anime, dibujos o animación, van a aparecer acá."
                },
            )
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)) {
            item {
                Text(
                    text = heading,
                    color = XuperTextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
                )
            }
            items(rows, key = { it.id }) { category ->
                ContentRow(
                    category = category,
                    onItemClick = onPlay,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
internal fun DiscoverScreen(sections: List<Section>, onPlay: (String) -> Unit) {
    val titles = remember(sections) {
        sections.flatMap { section -> section.categories.flatMap { it.items } }
            .filter { !it.isLive }
            .distinctBy { it.streamUrl }
    }

    if (titles.isEmpty()) {
        EmptyMessage(
            title = "DESCUBRIR",
            message = "Acá van a aparecer películas y series disponibles en tus listas. Las fuentes actuales parecen ser de TV en vivo; cuando agregues una lista VOD, sus títulos aparecerán en esta cuadrícula.",
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CardWidth),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(start = 40.dp, end = 32.dp, top = 28.dp, bottom = 40.dp),
        ) {
            item(key = "discover-heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "DESCUBRIR",
                    color = XuperTextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(titles, key = { it.id }) { title ->
                PosterCard(title = title, onClick = { onPlay(title.id) })
            }
        }
    }
}

@Composable
private fun EmptyMessage(title: String, message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = XuperTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(message, color = XuperTextSecondary, fontSize = 18.sp)
        }
    }
}

private fun normalizeName(value: String): String = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

private fun argentinaBrand(value: String): String? {
    val n = normalizeName(value)
    return when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> "tn"
        "cronica" in n -> "cronica"
        "el trece" in n || "eltrece" in n -> "el trece"
        "telefe" in n -> "telefe"
        n == "america" || n.startsWith("america ") || "america tv" in n -> "america"
        "gran hermano" in n || n == "gh" -> "gran hermano"
        else -> null
    }
}

private fun argentinaPriority(value: String): Int {
    val n = normalizeName(value)
    return when {
        n == "tn" || n.startsWith("tn ") || n.startsWith("tn-") -> 0
        "cronica" in n -> 1
        "el trece" in n || "eltrece" in n -> 2
        "telefe" in n -> 3
        n == "america" || n.startsWith("america ") || "america tv" in n -> 4
        "gran hermano" in n || n == "gh" -> 5
        else -> 100
    }
}


private fun isAnimation(title: Title): Boolean {
    val text = Normalizer.normalize(
        "${title.name} ${title.description}".lowercase(),
        Normalizer.Form.NFD,
    ).replace(Regex("\\p{Mn}+"), "")
    return listOf("anime", "animacion", "animation", "cartoon", "dibujos", "kids", "infantil", "manga")
        .any { text.contains(it) }
}
