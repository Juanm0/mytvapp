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
 * Para cambiar/agregar listas IPTV: editá esta lista. Cada M3uSource es un
 * ítem de la barra lateral. Para otra fuente (Xtream, backend propio)
 * implementá ContentRepository y devolvela acá.
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
    ),
)

class MainActivity : ComponentActivity() {

    private val repository: ContentRepository by lazy { buildRepository(filesDir) }
    private val tmdbCatalogService: TmdbCatalogService by lazy { TmdbCatalogService(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Limpia cachés/listas de catálogos antiguos que ya no se usan en esta versión.
        val activeCaches = setOf("catalog_0_argentina.tsv", "catalog_1_argentina_agregados.tsv")
        filesDir.listFiles()?.forEach { file ->
            val oldCatalogCache = file.name.startsWith("catalog_") && file.name.endsWith(".tsv") && file.name !in activeCaches
            val oldPlaylist = file.name in setOf("iptv-extra.m3u", "mi-lista.m3u", "lista-extendida.m3u", "peliculas-dominio-publico.m3u")
            if (oldCatalogCache || oldPlaylist) file.delete()
        }

        // Instala únicamente la lista argentina adicional. Las antiguas listas mixtas
        // dejaron de cargarse para mantener la app ligera; el catálogo VOD viene de TMDB.
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
                        if (title != null) DetailScreen(title = title, catalogService = tmdbCatalogService, onPlay = { navController.navigate("player/$id") })
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
                                onPlayNextEpisode = { nextId ->
                                    navController.popBackStack("main", false)
                                    navController.navigate("player/$nextId")
                                },
                                onChangeChannel = { nextId ->
                                    navController.popBackStack("main", false)
                                    navController.navigate("player/$nextId")
                                },
                                onPlaybackError = { lifecycleScope.launch { repository.markUnavailable(id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}
