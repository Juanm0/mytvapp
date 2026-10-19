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
import com.quilmes.xuper.ui.screens.MainScreen
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
    ),
)

class MainActivity : ComponentActivity() {

    private val repository: ContentRepository by lazy { buildRepository(filesDir) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Arranca ya mismo: lee el caché del disco y baja las listas en segundo plano
        lifecycleScope.launch { repository.refresh() }

        setContent {
            XuperTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
                            onPlay = { id -> navController.navigate("player/$id") },
                        )
                    }
                    composable(
                        route = "player/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        val title = remember(id) { repository.find(id) }
                        if (title != null) {
                            PlayerScreen(name = title.name, streamUrl = title.streamUrl)
                        }
                    }
                }
            }
        }
    }
}
