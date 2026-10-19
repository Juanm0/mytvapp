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
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.CardWidth
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.components.SkeletonRows
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

internal enum class LibraryMode { LIVE, MOVIES, ANIMATION }

@Composable
internal fun LibraryScreen(
    sections: List<Section>,
    mode: LibraryMode,
    onPlay: (String) -> Unit,
) {
    val rows = remember(sections, mode) {
        sections.flatMap { section ->
            section.categories.mapNotNull { category ->
                val filtered = category.items.filter { title ->
                    when (mode) {
                        LibraryMode.LIVE -> title.isLive && !isAnimation(title)
                        LibraryMode.MOVIES -> !title.isLive && !isAnimation(title)
                        LibraryMode.ANIMATION -> isAnimation(title)
                    }
                }
                if (filtered.isEmpty()) null else Category(
                    id = "${mode.name}-${category.id}",
                    title = if (mode == LibraryMode.LIVE) "${section.name} · ${category.title}" else category.title,
                    items = filtered,
                )
            }
        }
    }

    val heading = when (mode) {
        LibraryMode.LIVE -> "TV EN VIVO"
        LibraryMode.MOVIES -> "PELÍCULAS"
        LibraryMode.ANIMATION -> "ANIMACIÓN"
    }

    if (rows.isEmpty()) {
        if (sections.isEmpty()) {
            SkeletonRows()
        } else {
            EmptyMessage(
                title = when (mode) {
                    LibraryMode.LIVE -> "No hay canales disponibles"
                    LibraryMode.MOVIES -> "Todavía no hay películas"
                    LibraryMode.ANIMATION -> "No encontramos canales de animación"
                },
                message = when (mode) {
                    LibraryMode.LIVE -> "Las listas IPTV no devolvieron canales reproducibles."
                    LibraryMode.MOVIES -> "Las listas cargadas parecen contener TV en vivo, no películas almacenadas. Podés sumar una fuente VOD más adelante."
                    LibraryMode.ANIMATION -> "Cuando una lista incluya canales de anime, dibujos o animación, van a aparecer acá."
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

private fun isAnimation(title: Title): Boolean {
    val text = Normalizer.normalize(
        "${title.name} ${title.description}".lowercase(),
        Normalizer.Form.NFD,
    ).replace(Regex("\\p{Mn}+"), "")
    return listOf("anime", "animacion", "animation", "cartoon", "dibujos", "kids", "infantil", "manga")
        .any { text.contains(it) }
}
