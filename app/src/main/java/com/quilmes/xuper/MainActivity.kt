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
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.M3uSource
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
            homeRows = 5,
            priorityNames = listOf("TN", "La Nación", "LN+", "Crónica", "El Trece", "Eltrece", "Telefe", "América", "C5N", "Canal 9", "TV Pública", "Gran Hermano"),
            flattenIntoOneCategory = true,
            maxPerRow = 80,
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
            name = "Free-TV",
            url = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
            icon = "📡",
            homeRows = 4,
            priorityGroups = listOf("Argentina", "Spain", "Uruguay", "Chile"),
        ),
        M3uSource(
            name = "Español",
            url = "https://iptv-org.github.io/iptv/languages/spa.m3u",
            icon = "🌎",
            homeRows = 3,
        ),
        M3uSource(
            name = "Deportes",
            url = "https://iptv-org.github.io/iptv/categories/sports.m3u",
            icon = "⚽",
            homeRows = 2,
        ),
        M3uSource(
            name = "España",
            url = "https://iptv-org.github.io/iptv/countries/es.m3u",
            icon = "🇪🇸",
            homeRows = 2,
        ),
        M3uSource(
            name = "Mundo",
            url = "https://iptv-org.github.io/iptv/index.m3u",
            icon = "🌍",
            homeRows = 2,
        ),
        M3uSource(
            name = "Canales internacionales",
            url = File(cacheDir, "iptv-extra.m3u").toURI().toString(),
            icon = "🌐",
            homeRows = 3,
            priorityGroups = listOf("Argentina", "NEWS WORLD", "SPORTS", "Reality Show'S Live", "Cameras Ao Vivo"),
            maxPerRow = 60,
            maxRows = 80,
        ),
        M3uSource(
            name = "Películas de dominio público",
            url = "https://raw.githubusercontent.com/onlinem3u/publicdomainm3u/main/movies.m3u",
            icon = "🎬",
            homeRows = 3,
            maxPerRow = 30,
            maxRows = 80,
            contentHint = ContentKind.MOVIE,
        ),
        M3uSource(
            name = "Series de dominio público",
            url = "https://raw.githubusercontent.com/onlinem3u/publicdomainm3u/main/series.m3u",
            icon = "📺",
            homeRows = 3,
            maxPerRow = 30,
            maxRows = 80,
            contentHint = ContentKind.SERIES,
        ),
        M3uSource(
            name = "Lista extendida (experimental)",
            url = File(cacheDir, "lista-extendida.m3u").toURI().toString(),
            icon = "🧪",
            homeRows = 2,
            priorityGroups = listOf("Argentina", "Reality Show'S Live", "TVI PLAYER", "🟢 KÊNH PHIM", "Cameras Ao Vivo", "NEWS WORLD"),
            maxPerRow = 60,
            maxRows = 120,
            enrichMetadata = false,
        ),
        M3uSource(
            name = "Mi lista",
            url = File(cacheDir, "mi-lista.m3u").toURI().toString(),
            icon = "📋",
            homeRows = 3,
            priorityGroups = listOf("24/7", "Animation", "Cine", "Religioso", "Radio"),
            maxPerRow = 60,
            maxRows = 80,
        ),
    ),
)

class MainActivity : ComponentActivity() {

    private val repository: ContentRepository by lazy { buildRepository(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Copia la lista adicional empaquetada en el APK al almacenamiento privado.
        // La fuente se lee como archivo local, sin depender de un servidor para esta lista.
        runCatching {
            assets.open("iptv-extra.m3u").use { input ->
                File(filesDir, "iptv-extra.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar la lista IPTV adicional", error)
        }
        runCatching {
            assets.open("mi-lista.m3u").use { input ->
                File(filesDir, "mi-lista.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar la lista M3U del usuario", error)
        }
        runCatching {
            assets.open("peliculas-dominio-publico.m3u").use { input ->
                File(filesDir, "peliculas-dominio-publico.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar el catálogo local de películas", error)
        }

        runCatching {
            assets.open("argentina-extra.m3u").use { input ->
                File(filesDir, "argentina-extra.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar la lista argentina adicional", error)
        }
        runCatching {
            assets.open("lista-extendida.m3u").use { input ->
                File(filesDir, "lista-extendida.m3u").outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { error ->
            android.util.Log.e("XuperQuilmesTV", "No se pudo instalar la lista extendida", error)
        }
        // Las listas locales ya están copiadas; ahora sí se puede iniciar la carga.
        lifecycleScope.launch { repository.refresh() }

        setContent {
            XuperTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
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
                        if (title != null) DetailScreen(title = title, onPlay = { navController.navigate("player/$id") })
                    }
                    composable(
                        route = "player/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        val title = remember(id) { repository.find(id) }
                        if (title != null) {
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
