package com.quilmes.xuper.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Button
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.ui.components.NavItem
import com.quilmes.xuper.ui.components.SideBar
import com.quilmes.xuper.ui.theme.XuperAccentBright
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary

/** Navegación estilo streaming: solo quedan Inicio, Buscar, Argentina y utilidades personales. */
@Composable
fun MainScreen(repository: ContentRepository, catalogService: TmdbCatalogService, onPlay: (String) -> Unit) {
    val sections by repository.sections.collectAsState()
    var dest by rememberSaveable { mutableStateOf("home") }

    val items = listOf(
        NavItem("home", "⌂", "INICIO"),
        NavItem("search", "⌕", "BUSCAR"),
        NavItem("live", "🇦🇷", "ARGENTINA"),
        NavItem("favorites", "♡", "MI LISTA"),
        NavItem("continue", "▶", "CONTINUAR VIENDO"),
        NavItem("profile", "●", "PERFIL"),
    )

    BackHandler(enabled = dest != "home") { dest = "home" }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
            when (dest) {
                "home" -> HomeScreen(repository = repository, onPlay = onPlay)
                "search" -> SearchScreen(repository = repository, catalogService = catalogService, onPlay = onPlay)
                "live" -> LibraryScreen(sections = sections, mode = LibraryMode.ARGENTINA, onPlay = onPlay)
                "favorites" -> LibraryScreen(sections = sections, mode = LibraryMode.FAVORITES, onPlay = onPlay)
                "continue" -> LibraryScreen(sections = sections, mode = LibraryMode.CONTINUE, onPlay = onPlay)
                "profile" -> ProfileScreen()
            }
        }
        SideBar(items = items, selectedId = dest, onSelect = { dest = it }, modifier = Modifier.align(Alignment.CenterStart))
    }
}

@Composable
private fun ProfileScreen() {
    val context = LocalContext.current
    fun openStremio(uri: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://web.stremio.com/")))
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 52.dp, end = 48.dp, top = 52.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("¿Quién está viendo?", color = XuperTextPrimary, fontSize = 32.sp)
        Row(
            modifier = Modifier.background(XuperSurfaceVariant).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(modifier = Modifier.size(76.dp).background(XuperAccentBright), contentAlignment = Alignment.Center) {
                Text("X", color = XuperBackground, fontSize = 38.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Perfil principal", color = XuperTextPrimary, fontSize = 23.sp)
                Text("Xuper Quilmes TV · Android TV", color = XuperTextSecondary, fontSize = 16.sp)
            }
        }
        Text("Preferencias", color = XuperAccentBright, fontSize = 20.sp)
        Text("La app conserva tus favoritos y el historial local en este dispositivo.", color = XuperTextSecondary, fontSize = 16.sp)
        Text("Stremio · apaño temporal", color = XuperAccentBright, fontSize = 24.sp)
        Text(
            "Abrí Stremio para reproducir contenido de los addons instalados. El addon oficial Public Domain Movies ofrece películas de dominio público; está basado en torrents, así que requiere Stremio con soporte P2P. La integración de streams de addons directamente en Xuper queda pendiente.",
            color = XuperTextSecondary,
            fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { openStremio("stremio://") }) {
                Text("Abrir Stremio", fontSize = 16.sp)
            }
            Button(onClick = { openStremio("stremio://caching.stremio.net/publicdomainmovies.now.sh/manifest.json") }) {
                Text("Instalar addon de películas", fontSize = 16.sp)
            }
            Button(onClick = { openStremio("https://web.stremio.com/") }) {
                Text("Stremio Web", fontSize = 16.sp)
            }
        }
    }
}
