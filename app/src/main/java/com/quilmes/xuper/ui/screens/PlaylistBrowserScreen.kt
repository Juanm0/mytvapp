package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.Button
import com.quilmes.xuper.ui.components.ChannelGridCard
import com.quilmes.xuper.ui.components.PosterCard
import com.quilmes.xuper.ui.theme.*

/** Navegador de todas las listas M3U desde ARGENTINA; no agrega un destino a la barra lateral. */
@Composable
internal fun PlaylistBrowserScreen(
    sections: List<Section>,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
) {
    val playlists = remember(sections) {
        sections.filter { section ->
            section.id != "tmdb-catalog" && !section.name.equals("Argentina", true) &&
                !section.name.equals("xxx", true) && !section.name.equals("+18", true) && !section.name.equals("18+", true)
        }.flatMap { section -> section.categories.map { category ->
            Triple(section.name, category, category.items.filterNot { AdultContentSettings.isHiddenAdult(it) })
        } }.filter { it.third.isNotEmpty() }
    }
    var query by remember { mutableStateOf("") }
    var selectedKey by remember(playlists) { mutableStateOf(playlists.firstOrNull()?.second?.id) }
    val filtered = remember(playlists, query) {
        if (query.isBlank()) playlists else playlists.mapNotNull { (source, category, items) ->
            when {
                source.contains(query, true) || category.title.contains(query, true) -> Triple(source, category, items)
                else -> items.filter { it.name.contains(query, true) }
                    .takeIf { it.isNotEmpty() }?.let { Triple(source, category, it) }
            }
        }
    }
    val selected = filtered.firstOrNull { it.second.id == selectedKey } ?: filtered.firstOrNull()
    BackHandler { onBack() }
    Row(Modifier.fillMaxSize().background(XuperBackground).padding(start = 32.dp, top = 24.dp, end = 24.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(selected?.second?.title ?: "Listas IPTV", color = XuperTextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(selected?.let { "${it.first} · ${it.third.size} elementos" } ?: "No hay contenido disponible", color = XuperTextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp))
            if (selected != null) {
                LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(selected.third, key = { it.id }) { title ->
                        if (title.isLive) ChannelGridCard(title = title, badge = selected.first, onClick = { onPlay(title.id) })
                        else PosterCard(title = title, onClick = { onPlay(title.id) })
                    }
                }
            }
        }
        Column(Modifier.width(300.dp).fillMaxHeight().background(XuperSurfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp)) {
            Text("ÍNDICE DE LISTAS", color = XuperTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            BasicTextField(value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(color = XuperTextPrimary, fontSize = 16.sp), cursorBrush = SolidColor(XuperAccentBright),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
                decorationBox = { inner -> Box(Modifier.fillMaxWidth().background(XuperBackground, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    if (query.isBlank()) Text("Buscar en todas las listas…", color = XuperTextSecondary, fontSize = 14.sp)
                    inner()
                } })
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(filtered, key = { it.second.id }) { (source, category, items) ->
                    Button(onClick = { selectedKey = category.id }) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(category.title, color = XuperTextPrimary, maxLines = 2)
                            Text("$source · ${items.size}", color = XuperTextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Volver a Argentina") }
        }
    }
}
