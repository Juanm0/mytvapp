package com.quilmes.xuper

import android.os.Bundle
import android.net.Uri
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.M3uSource
import com.quilmes.xuper.data.PlaybackSourceRegistry
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.IptvAccountTextParser
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.ui.screens.MainScreen
import com.quilmes.xuper.ui.screens.DetailScreen
import com.quilmes.xuper.ui.screens.PlayerScreen
import com.quilmes.xuper.ui.screens.XuperBraveAdblock
import com.quilmes.xuper.ui.theme.XuperTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Las listas M3U alimentan el catálogo y la búsqueda, pero no agregan opciones
 * a la barra lateral. Para otra fuente (Xtream, backend propio), implementá
 * ContentRepository y devolvela acá.
 */
private fun buildRepository(cacheDir: File, assets: android.content.res.AssetManager): ContentRepository {
    val builtInSources = listOf(
        M3uSource(
            name = "Argentina",
            url = "https://iptv-org.github.io/iptv/countries/ar.m3u",
            icon = "🇦🇷",
            homeRows = 1,
            priorityNames = listOf("TN", "La Nación", "LN+", "Crónica", "El Trece", "Eltrece", "Telefe", "América", "C5N", "Canal 9", "TV Pública", "Gran Hermano"),
            flattenIntoOneCategory = true,
            enrichMetadata = false,
            maxPerRow = 100,
            maxRows = 1,
        ),
        M3uSource(
            name = "Argentina · agregados",
            url = File(cacheDir, "argentina-extra.m3u").toURI().toString(),
            icon = "🇦🇷",
            homeRows = 0,
            mergeIntoArgentina = true,
            enrichMetadata = false,
            maxPerRow = 100,
            maxRows = 1,
        ),
        M3uSource(
            name = "Películas gratis",
            url = "https://raw.githubusercontent.com/onlinem3u/publicdomainm3u/main/movies.m3u",
            icon = "🍿",
            homeRows = 2,
            contentHint = com.quilmes.xuper.data.ContentKind.MOVIE,
            enrichMetadata = false,
            maxPerRow = 60,
            maxRows = 4,
        ),
        M3uSource(
            name = "Series gratis",
            url = "https://raw.githubusercontent.com/onlinem3u/publicdomainm3u/main/series.m3u",
            icon = "📺",
            homeRows = 1,
            contentHint = com.quilmes.xuper.data.ContentKind.SERIES,
            enrichMetadata = false,
            maxPerRow = 60,
            maxRows = 4,
        ),
        M3uSource(
            name = "Mickflix canales",
            url = "https://raw.githubusercontent.com/mickflix/Mickflix-IPTV/master/playlist.m3u",
            icon = "🎞️",
            homeRows = 0,
            // Mickflix contiene canales lineales; no clasificarlos como películas/VOD.
            contentHint = null,
            enrichMetadata = false,
            maxPerRow = 80,
            maxRows = 120,
        ),
        M3uSource(
            name = "VOD original",
            url = File(cacheDir, "vod-original.m3u").toURI().toString(),
            icon = "🎬",
            homeRows = 3,
            // Lista mixta con títulos bajo demanda y algunos canales lineales.
            // Se muestra como VOD; los streams protegidos por DRM requieren licencia autorizada.
            contentHint = com.quilmes.xuper.data.ContentKind.OTHER_VOD,
            enrichMetadata = false,
            maxPerRow = 80,
            maxRows = 120,
        ),
    )
    val rawAccountText = runCatching {
        assets.open("iptv-accounts-raw.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")
    val accountSources = IptvAccountTextParser.parse(rawAccountText).accounts.mapIndexed { index, account ->
        M3uSource(
            name = "IPTV ${index + 1}",
            url = account.m3uUrl,
            icon = "📺",
            homeRows = 0,
            enrichMetadata = false,
            maxPerRow = 80,
            maxRows = 120,
            isUserIptvAccount = true,
        )
    }
    return M3uRepository(
        cacheDir = cacheDir,
        // Las cuentas del TXT siempre tienen prioridad sobre las fuentes públicas/integradas.
        sources = accountSources + loadCustomM3uSources(cacheDir) + builtInSources,
    )
}

/**
 * Fuentes opcionales definidas localmente en filesDir/iptv-sources.txt.
 * Formato por línea: nombre|URL_M3U_o_Xtream_get.php|icono|tipo|filas_inicio.
 * Esta configuración opcional se lee desde el almacenamiento privado de la app; las cuentas
 * principales editables para compilar se definen por separado en config/iptv-accounts-raw.txt.
 */
private fun loadCustomM3uSources(cacheDir: File): List<M3uSource> {
    val config = File(cacheDir, "iptv-sources.txt")
    if (!config.isFile) return emptyList()
    return runCatching {
        config.readLines(Charsets.UTF_8).mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val fields = line.split('|', limit = 5).map(String::trim)
            if (fields.size < 2) return@mapNotNull null
            val name = fields[0].takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = fields[1].takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
                ?: return@mapNotNull null
            val kind = when (fields.getOrNull(3)?.lowercase()) {
                "live" -> com.quilmes.xuper.data.ContentKind.LIVE
                "movie" -> com.quilmes.xuper.data.ContentKind.MOVIE
                "series" -> com.quilmes.xuper.data.ContentKind.SERIES
                "vod" -> com.quilmes.xuper.data.ContentKind.OTHER_VOD
                else -> null
            }
            M3uSource(
                name = name,
                url = url,
                icon = fields.getOrNull(2)?.takeIf(String::isNotBlank) ?: "📺",
                homeRows = fields.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 5) ?: 0,
                contentHint = kind,
                enrichMetadata = false,
                maxPerRow = 80,
                maxRows = 120,
            )
        }.distinctBy { it.url }
    }.onFailure { android.util.Log.w("XuperSources", "No se pudo leer iptv-sources.txt", it) }
        .getOrDefault(emptyList())
}

class MainActivity : ComponentActivity() {

    @Volatile private var iptvRefreshStarted = false
    @Volatile private var iptvRefreshInProgress = false
    private val localFilesReady = CompletableDeferred<Unit>()

    private fun requestIptvRefresh(force: Boolean = false) {
        if (iptvRefreshInProgress || (iptvRefreshStarted && !force)) return
        iptvRefreshStarted = true
        iptvRefreshInProgress = true
        lifecycleScope.launch {
            try {
                localFilesReady.await()
                runCatching { repository.refresh() }
                    .onFailure { android.util.Log.e("XuperQuilmesTV", "No se pudieron cargar las fuentes IPTV", it) }
            } finally {
                iptvRefreshInProgress = false
            }
        }
    }

    private val pendingDetailTitle = mutableStateOf<Title?>(null)
    private val repository: ContentRepository by lazy { buildRepository(filesDir, assets) }
    private val tmdbCatalogService: TmdbCatalogService by lazy { TmdbCatalogService(filesDir) }


    /** Corre en un hilo de fondo (Dispatchers.IO). */
    private fun prepareLocalFiles() {
        // Limpia cachés/listas de catálogos antiguos que ya no se usan en esta versión.
        filesDir.listFiles()?.forEach { file ->
            // No borrar catalog_*.tsv desconocidos: pueden pertenecer a fuentes configuradas por el usuario.
            val oldPlaylist = file.name in setOf("iptv-extra.m3u", "mi-lista.m3u", "lista-extendida.m3u", "peliculas-dominio-publico.m3u", "vod-original.m3u")
            val oldFailureBlacklist = file.name == "unavailable_streams.txt"
            if (oldPlaylist || oldFailureBlacklist) file.delete()
        }
        // Instala las listas locales (argentina adicional y VOD original) en el almacenamiento privado.
        // Las listas remotas se descargan y cachean; TMDB aporta metadatos, no los videos.
        for (name in listOf("argentina-extra.m3u", "vod-original.m3u")) {
            runCatching {
                assets.open(name).use { input ->
                    File(filesDir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { error ->
                android.util.Log.e("XuperQuilmesTV", "No se pudo instalar $name", error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XuperBraveAdblock.initialize(applicationContext)

        // La interfaz se dibuja YA (con filas "fantasma"); todo lo pesado va en segundo plano.
        // Antes se copiaban archivos acá y, sobre todo, setCatalogCategories() corría en el hilo
        // principal (lifecycleScope usa Dispatchers.Main): era lo que congelaba la app al abrir.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { prepareLocalFiles() }
            localFilesReady.complete(Unit)
            // El catálogo de TMDB sale del caché del disco casi al instante, así que no espera
            // a que terminen de bajar las listas M3U: Inicio muestra contenido enseguida.
            launch(Dispatchers.Default) {
                runCatching { tmdbCatalogService.loadCatalog() }
                    .onSuccess { categories -> (repository as? M3uRepository)?.setCatalogCategories(categories) }
                    .onFailure { error ->
                        android.util.Log.e("XuperQuilmesTV", "No se pudo cargar el catálogo TMDB", error)
                        (repository as? M3uRepository)?.finishInitialCatalogLoad()
                    }
            }
            // IPTV se carga bajo demanda: el catálogo visual de TMDB no espera a las listas.
        }

        setContent {
            XuperTheme {
                val navController = rememberNavController()
                val observedSections by repository.sections.collectAsState()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
                            catalogService = tmdbCatalogService,
                            onRequestIptv = { requestIptvRefresh() },
                            onPlay = { id ->
                                val selected = repository.find(id)
                                if (selected?.isLive == true) navController.navigate("player/$id")
                                else navController.navigate("detail/$id")
                            },
                        )
                    }
                    composable(
                        route = "detail/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        // Al abrir una ficha empezamos a consultar las listas. Cada fuente
                        // publica sus resultados al terminar, así que una coincidencia puede
                        // habilitar Reproducir antes de que finalice la descarga de todas.
                        LaunchedEffect(id) { requestIptvRefresh() }
                        val title = remember(id, pendingDetailTitle.value?.id, observedSections) {
                            repository.find(id) ?: pendingDetailTitle.value?.takeIf { it.id == id }
                        }
                        if (title != null) DetailScreen(
                            title = title,
                            catalogService = tmdbCatalogService,
                            onPlay = { playableId -> navController.navigate("player/$playableId?contentKey=${Uri.encode(playableId)}") },
                            onOpenTitle = { relatedTitle ->
                                pendingDetailTitle.value = relatedTitle
                                navController.navigate("detail/${relatedTitle.id}")
                            },
                            onPlayStream = { streamName, streamUrl ->
                                val encodedUrl = Base64.encodeToString(
                                    streamUrl.toByteArray(StandardCharsets.UTF_8),
                                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                                )
                                navController.navigate("external-player/${Uri.encode(streamName)}/$encodedUrl")
                            },
                            findPlayableEpisode = { seriesName, season, episode ->
                                (repository as? M3uRepository)?.findPlayableEpisode(seriesName, season, episode)
                            },
                        )
                    }
                    composable(
                        route = "external-player/{streamName}/{streamUrl}",
                        arguments = listOf(
                            navArgument("streamName") { type = NavType.StringType },
                            navArgument("streamUrl") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val streamName = entry.arguments?.getString("streamName").orEmpty()
                        val encodedUrl = entry.arguments?.getString("streamUrl").orEmpty()
                        val streamUrl = runCatching {
                            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
                        }.getOrDefault("")
                        if (streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) {
                            PlayerScreen(name = streamName, streamUrl = streamUrl, onExit = { navController.popBackStack() })
                        }
                    }
                    composable(
                        route = "player/{titleId}?contentKey={contentKey}",
                        arguments = listOf(
                            navArgument("titleId") { type = NavType.StringType },
                            navArgument("contentKey") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        val contentKey = entry.arguments?.getString("contentKey") ?: id
                        val isIptvLoading by repository.loading.collectAsState()
                        val title = remember(id, observedSections, pendingDetailTitle.value?.id) {
                            repository.find(id) ?: pendingDetailTitle.value?.takeIf { it.id == id }
                        }
                        val m3u = repository as? M3uRepository
                        val savedUrl = remember(contentKey) { PlaybackSourceRegistry.getPreferredUrl(this@MainActivity, contentKey) }
                        val alternatePlayers = remember(id, observedSections, title?.streamUrl, savedUrl) {
                            if (title == null) emptyList() else {
                                val matches = m3u?.getPlayableAlternatives(title).orEmpty() + title
                                matches.filter { it.streamUrl.isNotBlank() }
                                    .distinctBy { it.streamUrl }
                                    .sortedBy { if (it.streamUrl == savedUrl) 0 else 1 }
                            }
                        }
                        val preferredPlayback = alternatePlayers.firstOrNull { it.streamUrl == savedUrl }
                            ?: alternatePlayers.firstOrNull { it.streamUrl == title?.streamUrl }
                            ?: alternatePlayers.firstOrNull()

                        LaunchedEffect(id) {
                            if (title?.streamUrl.isNullOrBlank()) requestIptvRefresh()
                            if (title != null && !title.isLive) {
                                val prefs = getSharedPreferences("xuper-library", MODE_PRIVATE)
                                val recent = prefs.getString("recent", "").orEmpty().split("|")
                                    .filter { it.isNotBlank() && it != id }.toMutableList()
                                recent.add(id)
                                prefs.edit().putString("recent", recent.takeLast(30).joinToString("|")).apply()
                            }
                        }

                        if (title != null && preferredPlayback != null) {
                            val playbackId = preferredPlayback.id
                            val channelQueue = remember(playbackId) { m3u?.getChannelQueue(playbackId).orEmpty() }
                            val nextEpisode = remember(playbackId) { m3u?.getNextEpisode(playbackId) }
                            PlayerScreen(
                                name = title.name,
                                streamUrl = preferredPlayback.streamUrl,
                                streamHeaders = preferredPlayback.requestHeaders,
                                channelQueue = channelQueue,
                                currentTitleId = playbackId,
                                playbackKey = contentKey,
                                nextEpisode = nextEpisode,
                                alternatePlayers = alternatePlayers,
                                onChangePlayer = { alternativeId ->
                                    navController.popBackStack("main", false)
                                    navController.navigate("player/$alternativeId")
                                },
                                onPlayNextEpisode = { nextId ->
                                    navController.popBackStack("main", false)
                                    navController.navigate("player/$nextId")
                                },
                                onChangeChannel = { nextId ->
                                    navController.popBackStack("main", false)
                                    navController.navigate("player/$nextId")
                                },
                                onExit = { navController.popBackStack() },
                            )
                        } else {
                            Box(
                                Modifier.fillMaxSize().background(Color(0xFF0B0F17)).padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text(
                                        when {
                                            title == null -> "No se pudo cargar la ficha."
                                            isIptvLoading -> "Buscando una fuente en las listas IPTV…"
                                            else -> "No encontramos una fuente IPTV coincidente en las listas cargadas."
                                        },
                                        color = Color.White,
                                    )
                                    Button(enabled = !isIptvLoading, onClick = { requestIptvRefresh(force = true) }) { Text("Reintentar búsqueda") }
                                    Button(onClick = { navController.popBackStack() }) { Text("Volver a la ficha") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
