package com.quilmes.xuper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.M3uSource
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.ui.screens.MainScreen
import com.quilmes.xuper.ui.screens.DetailScreen
import com.quilmes.xuper.ui.screens.PlayerScreen
import com.quilmes.xuper.ui.theme.XuperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Las listas M3U alimentan el catálogo y la búsqueda, pero no agregan opciones
 * a la barra lateral. Para otra fuente (Xtream, backend propio), implementá
 * ContentRepository y devolvela acá.
 */
private fun buildRepository(cacheDir: File): ContentRepository = M3uRepository(
    cacheDir = cacheDir,
    sources = listOf(
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
    ),
)

class MainActivity : ComponentActivity() {

    private val repository: ContentRepository by lazy { buildRepository(filesDir) }
    private val tmdbCatalogService: TmdbCatalogService by lazy { TmdbCatalogService(filesDir) }


    /** Corre en un hilo de fondo (Dispatchers.IO). */
    private fun prepareLocalFiles() {
        // Limpia cachés/listas de catálogos antiguos que ya no se usan en esta versión.
        val activeCaches = setOf("catalog_0_argentina.tsv", "catalog_1_argentina_agregados.tsv", "catalog_2_pel_culas_gratis.tsv", "catalog_3_series_gratis.tsv", "catalog_4_mickflix_canales.tsv", "catalog_5_vod_original.tsv")
        filesDir.listFiles()?.forEach { file ->
            val oldCatalogCache = file.name.startsWith("catalog_") && file.name.endsWith(".tsv") && file.name !in activeCaches
            val oldPlaylist = file.name in setOf("iptv-extra.m3u", "mi-lista.m3u", "lista-extendida.m3u", "peliculas-dominio-publico.m3u", "vod-original.m3u")
            val oldFailureBlacklist = file.name == "unavailable_streams.txt"
            if (oldCatalogCache || oldPlaylist || oldFailureBlacklist) file.delete()
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

        // La interfaz se dibuja YA (con filas "fantasma"); todo lo pesado va en segundo plano.
        // Antes se copiaban archivos acá y, sobre todo, setCatalogCategories() corría en el hilo
        // principal (lifecycleScope usa Dispatchers.Main): era lo que congelaba la app al abrir.
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { prepareLocalFiles() }
            // El catálogo de TMDB sale del caché del disco casi al instante, así que no espera
            // a que terminen de bajar las listas M3U: Inicio muestra contenido enseguida.
            launch(Dispatchers.Default) {
                runCatching { tmdbCatalogService.loadCatalog() }
                    .onSuccess { categories -> (repository as? M3uRepository)?.setCatalogCategories(categories) }
                    .onFailure { android.util.Log.e("XuperQuilmesTV", "No se pudo cargar el catálogo TMDB", it) }
            }
            repository.refresh()
        }

        setContent {
            XuperTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
                            catalogService = tmdbCatalogService,
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
                        val title = remember(id) { repository.find(id) }
                        if (title != null) DetailScreen(
                            title = title,
                            catalogService = tmdbCatalogService,
                            onPlay = { playableId -> navController.navigate("player/$playableId") },
                            findPlayableEpisode = { seriesName, season, episode ->
                                (repository as? M3uRepository)?.findPlayableEpisode(seriesName, season, episode)
                            },
                        )
                    }
                    composable(
                        route = "player/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        val title = remember(id) { repository.find(id) }
                        if (title != null && title.streamUrl.isNotBlank()) {
                            LaunchedEffect(id) {
                                if (!title.isLive) {
                                    val prefs = getSharedPreferences("xuper-library", MODE_PRIVATE)
                                    val recent = prefs.getString("recent", "").orEmpty().split("|").filter { it.isNotBlank() && it != id }.toMutableList()
                                    recent.add(id)
                                    prefs.edit().putString("recent", recent.takeLast(30).joinToString("|")).apply()
                                }
                            }
                            val m3u = repository as? M3uRepository
                            val channelQueue = remember(id) { m3u?.getChannelQueue(id).orEmpty() }
                            val nextEpisode = remember(id) { m3u?.getNextEpisode(id) }
                            val alternatePlayers = remember(id) { m3u?.getPlayableAlternatives(id).orEmpty() }
                            PlayerScreen(
                                name = title.name,
                                streamUrl = title.streamUrl,
                                streamHeaders = title.requestHeaders,
                                channelQueue = channelQueue,
                                currentTitleId = id,
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
                            )
                        }
                    }
                }
            }
        }
    }
}
