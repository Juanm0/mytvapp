package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.ui.components.Button
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.launch

private data class PlaylistIndexEntry(
    val id: String,
    val source: String,
    val category: Category,
)

/**
 * Navegador de listas M3U: muestra todas las fuentes cargadas, todas sus categorías y un índice
 * lateral para saltar directamente a cualquier grupo. La búsqueda recorre el contenido completo
 * de las listas visibles, no solo las filas que se muestran en Inicio.
 */
@Composable
fun PlaylistBrowserScreen(
    repository: ContentRepository,
    onPlay: (String) -> Unit,
) {
    val sections by repository.sections.collectAsState()
    val loading by repository.loading.collectAsState()
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val allEntries = remember(sections) {
        sections.asSequence()
            .filterNot { it.id == "tmdb-catalog" }
            .filterNot { it.name.equals("xxx", true) || it.name.equals("+18", true) || it.name.equals("18+", true) }
            .filter { it.categories.isNotEmpty() }
            .flatMap { section ->
                section.categories.asSequence().filter { it.items.isNotEmpty() }.map { category ->
                    PlaylistIndexEntry(
                        id = "${section.id}:${category.id}",
                        source = section.name,
                        category = category,
                    )
                }
            }
            .toList()
    }

    val visibleEntries = remember(allEntries, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) allEntries
        else {
            allEntries.mapNotNull { entry ->
                val matches = entry.category.items.filter {
                    it.name.contains(q, ignoreCase = true) ||
                        it.description.contains(q, ignoreCase = true) ||
                        entry.category.title.contains(q, ignoreCase = true) ||
                        entry.source.contains(q, ignoreCase = true)
                }
                matches.takeIf { it.isNotEmpty() }?.let { found ->
                    entry.copy(
                        id = "search:${entry.id}",
                        category = entry.category.copy(
                            id = "search-${entry.category.id}",
                            title = "${entry.category.title} · ${found.size}",
                            items = found,
                        ),
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().background(XuperBackground).padding(start = 30.dp, end = 22.dp, top = 24.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Text("LISTAS IPTV", color = XuperTextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                if (query.isBlank()) "${allEntries.size} grupos disponibles · ${sections.count { it.id != "tmdb-catalog" }} listas cargadas"
                else "${visibleEntries.sumOf { it.category.items.size }} resultados en las listas",
                color = XuperTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp),
            )
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = XuperTextPrimary, fontSize = 19.sp),
                cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.White),
                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).clip(RoundedCornerShape(9.dp))
                    .background(XuperSurfaceVariant).padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isBlank()) Text("Buscar canales, películas o series en todas las listas…", color = XuperTextSecondary, fontSize = 17.sp)
                        inner()
                    }
                },
            )
            if (visibleEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (loading && sections.isEmpty()) "Cargando todas las listas M3U…"
                        else if (sections.isEmpty()) "Todavía no se cargaron listas. Actualizá las fuentes e intentá de nuevo."
                        else "No hay coincidencias en las listas cargadas.",
                        color = XuperTextSecondary,
                        fontSize = 18.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleEntries, key = { it.id }) { entry ->
                        ContentRow(
                            category = entry.category.copy(title = "${entry.source}  ·  ${entry.category.title}"),
                            onItemClick = onPlay,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.width(270.dp).fillMaxHeight()
                .clip(RoundedCornerShape(12.dp)).background(XuperSurfaceVariant).padding(12.dp),
        ) {
            Text("ÍNDICE", color = XuperTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visibleEntries, key = { "index-${it.id}" }) { entry ->
                    Button(
                        onClick = {
                            val index = visibleEntries.indexOfFirst { it.id == entry.id }
                            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${entry.source}  ›  ${entry.category.title}",
                            color = XuperTextPrimary,
                            fontSize = 13.sp,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
