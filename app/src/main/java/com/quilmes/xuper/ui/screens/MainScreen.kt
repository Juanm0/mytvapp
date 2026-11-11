package com.quilmes.xuper.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
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
import androidx.tv.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.DeveloperMode
import com.quilmes.xuper.data.DeveloperUnlockSound
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.JkanimeService
import com.quilmes.xuper.ui.components.NavItem
import com.quilmes.xuper.ui.components.PinDialog
import com.quilmes.xuper.ui.components.SideBar
import com.quilmes.xuper.ui.theme.XuperAccent
import com.quilmes.xuper.ui.theme.XuperBackground
import com.quilmes.xuper.ui.theme.XuperSurfaceVariant
import com.quilmes.xuper.ui.theme.XuperTextPrimary
import com.quilmes.xuper.ui.theme.XuperTextSecondary
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.tv.material3.ClickableSurfaceDefaults
import com.quilmes.xuper.ui.components.Surface

/** Navegación estilo streaming: solo quedan Inicio, Buscar, Argentina y utilidades personales. */
@Composable
fun MainScreen(
    repository: ContentRepository,
    catalogService: TmdbCatalogService,
    onPlay: (String) -> Unit,
    onRequestIptv: () -> Unit = {},
    jkanimeService: JkanimeService,
    onPlayAnimeEpisode: (title: String, streamUrl: String, episodeUrl: String) -> Unit,
    adultAnimeService: JkanimeService? = null,
    /** Inicio: nombre a mostrar e ID TMDB del título/episodio. */
    onPlayHomeTitle: (title: String, catalogId: String) -> Unit = { _, _ -> },
) {
    val sections by repository.sections.collectAsState()
    val repositoryLoading by repository.loading.collectAsState()
    var dest by rememberSaveable { mutableStateOf("home") }
    var showPlaylistBrowser by rememberSaveable { mutableStateOf(false) }

    // La sección +18 existe en la barra lateral solo con el ajuste activado (Perfil, protegido con PIN).
    val adultEnabled by AdultContentSettings.enabledFlow.collectAsState()
    // LAB (🧪) está oculto: solo existe en la barra con las opciones de desarrollador desbloqueadas (Easter Egg,
    // ver DeveloperMode). La pantalla y sus funciones se conservan; solo se quita su acceso.
    val devEnabled by DeveloperMode.enabledFlow.collectAsState()
    val kittenArmed by DeveloperMode.kittenArmedFlow.collectAsState()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            notice = null
        }
    }
    val items = buildList {
        add(NavItem("home", "⌂", "INICIO"))
        add(NavItem("search", "⌕", "BUSCAR"))
        add(NavItem("live", "🇦🇷", "ARGENTINA"))
        add(NavItem("anime", "🎌", "ANIME"))
        if (devEnabled) add(NavItem("lab", "🧪", "LAB"))
        add(NavItem("favorites", "♡", "MI LISTA"))
        add(NavItem("continue", "▶", "CONTINUAR VIENDO"))
        add(NavItem("profile", "●", "PERFIL"))
        if (adultEnabled) add(NavItem("adult", "🔞", "+18"))
    }
    LaunchedEffect(adultEnabled) { if (!adultEnabled && dest == "adult") dest = "home" }
    // Si se apaga el modo desarrollador (o quedó "lab" guardado de antes), LAB deja de mostrarse.
    LaunchedEffect(devEnabled) { if (!devEnabled && dest == "lab") dest = "home" }

    BackHandler(enabled = showPlaylistBrowser) { showPlaylistBrowser = false }
    BackHandler(enabled = dest != "home" && !showPlaylistBrowser) { dest = "home" }

    // La cola de "canal anterior/siguiente" de la pestaña Argentina solo vale para lo que se toca ahí:
    // desde cualquier otra pantalla se limpia para que el reproductor use su cola de siempre.
    val plainPlay: (String) -> Unit = { id ->
        com.quilmes.xuper.data.ChannelQueueStore.clear()
        onPlay(id)
    }

    Box(modifier = Modifier.fillMaxSize().background(XuperBackground)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
            when (dest) {
                // Inicio usa el catálogo TMDB y un único flujo de reproducción VideoAPI + subtítulos CineSrc.
                "home" -> LabScreen(catalogService = catalogService, onPlay = onPlayHomeTitle)
                "search" -> LabScreen(catalogService = catalogService, onPlay = onPlayHomeTitle, startInSearch = true)
                "live" -> if (showPlaylistBrowser) PlaylistBrowserScreen(sections = sections, onPlay = plainPlay, onBack = { showPlaylistBrowser = false })
                    else ArgentinaScreen(sections = sections, onPlay = onPlay, onBrowsePlaylists = { showPlaylistBrowser = true })
                "anime" -> AnimeScreen(service = jkanimeService, adultService = adultAnimeService, onPlayEpisode = onPlayAnimeEpisode)
                "lab" -> if (devEnabled) HomeScreen(repository = repository, onPlay = plainPlay)
                "favorites" -> LibraryScreen(sections = sections, mode = LibraryMode.FAVORITES, onPlay = plainPlay)
                "continue" -> LibraryScreen(sections = sections, mode = LibraryMode.CONTINUE, onPlay = plainPlay)
                "adult" -> if (adultEnabled) AdultScreen(sections = sections, loading = repositoryLoading, onPlay = plainPlay)
                "profile" -> ProfileScreen()
            }
        }
        SideBar(items = items, selectedId = dest, onSelect = { selected ->
            // Easter Egg, paso 1: 3 toques seguidos en INICIO => sonido y gatito habilitado.
            if (selected == "home") {
                if (DeveloperMode.onHomePressed() == DeveloperMode.Event.HOME_UNLOCKED) DeveloperUnlockSound.play(context)
            } else {
                DeveloperMode.onOtherNavPressed()
            }
            // +18 también dispara la carga inicial de las listas M3U locales.
            // Antes solo se refrescaban al entrar en ARGENTINA, dejando xxx.m3u vacío.
            if (selected == "live" || selected == "adult" || (selected == "search" && adultEnabled)) onRequestIptv()
            if (selected != "live") showPlaylistBrowser = false
            dest = selected
        },
            modifier = Modifier.align(Alignment.CenterStart),
            // Easter Egg, paso 2: el gatito solo cuenta toques una vez habilitado (antes no hace nada).
            logoEnabled = kittenArmed && !devEnabled,
            logoTogglesLabels = devEnabled,
            onLogoClick = {
                when (DeveloperMode.onKittenPressed()) {
                    DeveloperMode.Event.KITTEN_HINT -> notice = "Usted es desarrollador en 3.2.1"
                    DeveloperMode.Event.DEVELOPER_ENABLED -> notice = "Opciones de desarrollador habilitadas"
                    else -> Unit
                }
            },
        )
        notice?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 72.dp, bottom = 32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(XuperSurfaceVariant)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(text, color = XuperTextPrimary, fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun ProfileScreen() {
    // Perfil simple: no depende de servicios externos ni de integraciones retiradas.
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 52.dp, end = 48.dp, top = 52.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("¿Quién está viendo?", fontSize = 32.sp)
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier.size(76.dp).background(XuperBackground),
                contentAlignment = Alignment.Center,
            ) {
                Text("X", fontSize = 38.sp)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Perfil principal", fontSize = 23.sp)
                Text("Xuper Quilmes TV · Android TV", fontSize = 16.sp)
            }
        }
        Text("Preferencias", fontSize = 20.sp)
        Text(
            "Tus favoritos, historial y fuentes de reproducción se gestionan localmente en este dispositivo.",
            fontSize = 16.sp,
        )
        IptvProfilePreference()
        // Mantener siempre las opciones +18 al final de Perfil.
        AdultContentPreference()
    }
}

@Composable
private fun IptvProfilePreference() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("xuper-iptv", android.content.Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(prefs.getString("profile", "fast") ?: "fast") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reproducción IPTV", fontSize = 20.sp)
        Text(
            "Elegí cómo administrar el buffer de los canales en vivo. Se aplica al abrir el próximo canal.",
            fontSize = 14.sp,
            color = XuperTextSecondary,
        )
        PreferenceRow(
            check = if (selected == "fast") "●" else "○",
            title = "Rápido",
            subtitle = "Predeterminado: inicia más rápido; necesita una conexión estable.",
            onClick = {
                selected = "fast"
                prefs.edit().putString("profile", selected).apply()
            },
        )
        PreferenceRow(
            check = if (selected == "normal") "●" else "○",
            title = "Normal",
            subtitle = "Equilibrio entre inicio y tolerancia a cortes.",
            onClick = {
                selected = "normal"
                prefs.edit().putString("profile", selected).apply()
            },
        )
        PreferenceRow(
            check = if (selected == "stable") "●" else "○",
            title = "Estable",
            subtitle = "Más buffer para conexiones variables; puede tardar más en iniciar.",
            onClick = {
                selected = "stable"
                prefs.edit().putString("profile", selected).apply()
            },
        )
    }
}

private enum class PinStep { CREATE_AND_ENABLE, VERIFY_TO_ENABLE, VERIFY_TO_CHANGE, CREATE_NEW }

/** Check "Mostrar contenido adulto" (+18), apagado por defecto. Activarlo exige PIN; desactivarlo no. */
@Composable
private fun AdultContentPreference() {
    val enabled by AdultContentSettings.enabledFlow.collectAsState()
    var step by remember { mutableStateOf<PinStep?>(null) }
    var hasPin by remember { mutableStateOf(AdultContentSettings.hasPin()) }

    PreferenceRow(
        check = if (enabled) "☑" else "☐",
        title = "Mostrar contenido adulto (+18)",
        subtitle = if (enabled) "Activado: aparece la sección +18 y las búsquedas/fichas incluyen contenido adulto."
            else "Desactivado: la sección +18 queda oculta. Para activarlo se pide un PIN.",
        onClick = {
            if (enabled) AdultContentSettings.disable()
            else step = if (hasPin) PinStep.VERIFY_TO_ENABLE else PinStep.CREATE_AND_ENABLE
        },
    )
    if (hasPin) {
        PreferenceRow(
            check = "🔒", title = "Cambiar PIN", subtitle = "Se pide el PIN actual.",
            onClick = { step = PinStep.VERIFY_TO_CHANGE },
        )
    }

    fun verdict(result: AdultContentSettings.PinResult, onOk: () -> Unit): String? = when (result) {
        AdultContentSettings.PinResult.OK -> { onOk(); null }
        AdultContentSettings.PinResult.WRONG -> "PIN incorrecto."
        AdultContentSettings.PinResult.LOCKED -> "Demasiados intentos. Esperá ${AdultContentSettings.lockSecondsLeft()} s."
    }

    step?.let { current ->
        key(current) {
            when (current) {
                PinStep.CREATE_AND_ENABLE -> PinDialog(
                    title = "Creá un PIN", message = "Elegí un PIN de 4 dígitos para proteger el contenido +18.",
                    confirmEntry = true, onDismiss = { step = null },
                ) { pin ->
                    if (AdultContentSettings.createPinAndEnable(pin)) { hasPin = true; step = null; null } else "PIN inválido."
                }
                PinStep.VERIFY_TO_ENABLE -> PinDialog(
                    title = "Ingresá tu PIN", message = "Necesitás el PIN para activar el contenido +18.",
                    confirmEntry = false, onDismiss = { step = null },
                ) { pin -> verdict(AdultContentSettings.enableWithPin(pin)) { step = null } }
                PinStep.VERIFY_TO_CHANGE -> PinDialog(
                    title = "PIN actual", message = "Ingresá tu PIN actual para cambiarlo.",
                    confirmEntry = false, onDismiss = { step = null },
                ) { pin -> verdict(AdultContentSettings.verifyPin(pin)) { step = PinStep.CREATE_NEW } }
                PinStep.CREATE_NEW -> PinDialog(
                    title = "Nuevo PIN", message = "Elegí un PIN de 4 dígitos.",
                    confirmEntry = true, onDismiss = { step = null },
                ) { pin -> if (AdultContentSettings.setPin(pin)) { step = null; null } else "PIN inválido." }
            }
        }
    }
}

@Composable
private fun PreferenceRow(check: String, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = XuperSurfaceVariant,
            contentColor = XuperTextPrimary,
            focusedContainerColor = XuperAccent,
            focusedContentColor = Color.White,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(check, fontSize = 26.sp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 19.sp)
                Text(subtitle, fontSize = 14.sp, color = XuperTextSecondary)
            }
        }
    }
}
