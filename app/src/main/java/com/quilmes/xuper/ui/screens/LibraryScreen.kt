package com.quilmes.xuper.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.Category
import com.quilmes.xuper.data.Section
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.ui.components.ChannelGridCard
import com.quilmes.xuper.ui.components.ChannelGridCardHeight
import com.quilmes.xuper.ui.components.ContentRow
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

internal enum class LibraryMode { FAVORITES, CONTINUE }

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
    LaunchedEffect(sections, mode, favoriteIds, recentIds, savedRecentTitles) {
        val computed = withContext(Dispatchers.Default) {
            buildLibraryRows(sections, mode, favoriteIds, recentIds, recentOrder, savedRecentTitles)
        }
        rows = computed
    }
    val heading = when (mode) {
        LibraryMode.FAVORITES -> "MI LISTA"
        LibraryMode.CONTINUE -> "CONTINUAR VIENDO"
    }
    val emptyTitle = when (mode) {
        LibraryMode.FAVORITES -> "Todavía no guardaste favoritos"
        LibraryMode.CONTINUE -> "Todavía no hay contenido para continuar"
    }
    val emptyMessage = when (mode) {
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
    savedRecentTitles: List<Title>,
): List<Category> {
    val allTitles by lazy { sections.flatMap { it.categories }.flatMap { it.items }.distinctBy { it.id } }
    return when (mode) {
        LibraryMode.FAVORITES -> listOfNotNull(
            allTitles.filter { it.id in favoriteIds && !AdultContentSettings.isHiddenAdult(it) }.takeIf { it.isNotEmpty() }
                ?.let { Category("favorites", "Mi lista", it) }
        )
        LibraryMode.CONTINUE -> listOfNotNull(
            (recentOrder.mapNotNull { id -> allTitles.firstOrNull { it.id == id && !it.isLive } ?: savedRecentTitles.firstOrNull { it.id == id && !it.isLive } }
                .distinctBy { it.id })
                .filterNot { AdultContentSettings.isHiddenAdult(it) }
                .takeIf { it.isNotEmpty() }?.let { Category("continue", "Continuar viendo", it) }
        )
    }
}
