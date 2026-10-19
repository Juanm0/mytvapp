package com.quilmes.xuper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.launch
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
            name = "VOD General",
            url = "https://raw.githubusercontent.com/jromero88/iptv/master/VOD.m3u",
            icon = "🎬",
            homeRows = 0,
            contentHint = com.quilmes.xuper.data.ContentKind.OTHER_VOD,
            maxPerRow = 60,
            maxRows = 80,
        ),
        M3uSource(
            name = "Agrupado por contenido",
            url = "https://raw.githubusercontent.com/jromero88/iptv/master/index.content.m3u",
            icon = "🗂️",
            homeRows = 0,
            contentHint = com.quilmes.xuper.data.ContentKind.OTHER_VOD,
            maxPerRow = 60,
            maxRows = 80,
        ),
        M3uSource(
            name = "Películas",
            url = "https://raw.githubusercontent.com/jromero88/iptv/master/categories/movies.m3u",
            icon = "🍿",
            homeRows = 0,
            contentHint = com.quilmes.xuper.data.ContentKind.MOVIE,
            maxPerRow = 60,
            maxRows = 40,
        ),
        M3uSource(
            name = "Mickflix VOD",
            url = "https://raw.githubusercontent.com/mickflix/Mickflix-IPTV/master/playlist.m3u",
            icon = "🎞️",
            homeRows = 0,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Limpia cachés/listas de catálogos antiguos que ya no se usan en esta versión.
        val activeCaches = setOf("catalog_0_argentina.tsv", "catalog_1_argentina_agregados.tsv", "catalog_2_vod_general.tsv", "catalog_3_agrupado_por_contenido.tsv", "catalog_4_peliculas.tsv", "catalog_4_pel_culas.tsv", "catalog_5_mickflix_vod.tsv")
        filesDir.listFiles()?.forEach { file ->
            val oldCatalogCache = file.name.startsWith("catalog_") && file.name.endsWith(".tsv") && file.name !in activeCaches
            val oldPlaylist = file.name in setOf("iptv-extra.m3u", "mi-lista.m3u", "lista-extendida.m3u", "peliculas-dominio-publico.m3u")
            val oldFailureBlacklist = file.name == "unavailable_streams.txt"
            if (oldCatalogCache || oldPlaylist || oldFailureBlacklist) file.delete()
        }

        // Instala únicamente la lista argentina adicional. Las listas VOD se descargan
        // desde sus URLs y se cachean; TMDB aporta metadatos, no los videos.
        runCatching {
            assets.open("argentina-extra.m3u").use { input ->
                File(filesDir, "argentina-extra.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar la lista argentina adicional", error)
        }
        // Las listas locales ya están copiadas; ahora sí se puede iniciar la carga.
        lifecycleScope.launch {
            repository.refresh()
            runCatching { tmdbCatalogService.loadCatalog() }
                .onSuccess { categories -> (repository as? M3uRepository)?.setCatalogCategories(categories) }
                .onFailure { android.util.Log.e("XuperQuilmesTV", "No se pudo cargar el catálogo TMDB", it) }
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
                            if (!title.isLive) {
                                val prefs = getSharedPreferences("xuper-library", MODE_PRIVATE)
                                val recent = prefs.getString("recent", "").orEmpty().split("|").filter { it.isNotBlank() && it != id }.toMutableList()
                                recent.add(id)
                                prefs.edit().putString("recent", recent.takeLast(30).joinToString("|")).apply()
                            }
                            PlayerScreen(
                                name = title.name,
                                streamUrl = title.streamUrl,
                                channelQueue = (repository as? M3uRepository)?.getChannelQueue(id).orEmpty(),
                                currentTitleId = id,
                                nextEpisode = (repository as? M3uRepository)?.getNextEpisode(id),
                                alternatePlayers = (repository as? M3uRepository)?.getPlayableAlternatives(id).orEmpty(),
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
