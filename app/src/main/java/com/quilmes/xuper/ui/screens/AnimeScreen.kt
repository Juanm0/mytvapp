package com.quilmes.xuper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AnimeRow
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.CardWidth
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import kotlinx.coroutines.delay

/**
 * Sección ANIME: filas de AniList (tendencias, en emisión, populares, mejor valoradas) y buscador.
 * Si el anime existe en TMDb se muestra su ficha de TMDb; si no, la de AniList (ver AnimeCatalogService).
 */
@Composable
fun AnimeScreen(
    repository: ContentRepository,
    catalogService: TmdbCatalogService,
    onPlay: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    fun register(titles: List<Title>) { (repository as? M3uRepository)?.registerLookupTitles(titles) }

    // Las filas aparecen una por una a medida que se resuelven (la primera vez hay que cruzar con TMDb).
    LaunchedEffect(Unit) {
        AnimeRow.values().forEach { row ->
            val titles = catalogService.anime.loadRow(row)
            if (titles.isNotEmpty()) {
                register(titles)
                rows = rows + Category("anime-${row.name}", row.label, titles)
            }
        }
        loading = false
    }
    LaunchedEffect(query) {
        delay(300)
        if (query.isBlank()) { results = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        val found = catalogService.anime.search(query)
        register(found)
        results = found
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 32.dp)) {
        Text("Anime", color = XuperTextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 48.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = XuperTextPrimary, fontSize = 22.sp),
            cursorBrush = SolidColor(XuperAccentBright),
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 14.dp),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(XuperSurfaceVariant).padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    if (query.isEmpty()) Text("🔍  Buscar anime…", color = XuperTextSecondary, fontSize = 22.sp)
                    inner()
                }
            },
        )
        when {
            query.isNotBlank() -> {
                if (searching) Text("Buscando…", color = XuperTextSecondary, fontSize = 18.sp, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
                else if (results.isEmpty()) Text("Sin resultados para \"$query\"", color = XuperTextSecondary, fontSize = 20.sp, modifier = Modifier.padding(start = 48.dp, top = 24.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CardWidth),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 20.dp),
                ) { gridItems(results, key = { it.id }) { t -> PosterCard(title = t, onClick = { onPlay(t.id) }) } }
            }
            rows.isEmpty() && !loading -> Text(
                "No se pudo cargar el catálogo de anime. Revisá la conexión y probá de nuevo.",
                color = XuperTextSecondary, fontSize = 18.sp, modifier = Modifier.padding(start = 48.dp, top = 24.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp)) {
                items(rows, key = { it.id }) { c ->
                    ContentRow(category = c, onItemClick = onPlay, modifier = Modifier.padding(top = 12.dp))
                }
                if (loading) item {
                    Text("Cargando más…", color = XuperTextSecondary, fontSize = 16.sp, modifier = Modifier.padding(start = 48.dp, top = 16.dp))
                }
            }
        }
    }
}
