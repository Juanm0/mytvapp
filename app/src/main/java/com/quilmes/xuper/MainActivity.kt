package com.quilmes.xuper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.MockContentRepository
import com.quilmes.xuper.ui.screens.HomeScreen
import com.quilmes.xuper.ui.screens.PlayerScreen
import com.quilmes.xuper.ui.theme.XuperTheme
import kotlinx.coroutines.runBlocking

/**
 * Para conectar tu propia fuente de datos (IPTV u otra), cambiá esta línea
 * por tu implementación de ContentRepository. El resto de la app no
 * necesita tocarse.
 */
private fun buildRepository(): ContentRepository = MockContentRepository()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val repository = remember { buildRepository() }
            XuperTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            repository = repository,
                            onPlay = { titleId ->
                                navController.navigate("player/$titleId")
                            },
                        )
                    }
                    composable(
                        route = "player/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val titleId = backStackEntry.arguments?.getString("titleId") ?: return@composable
                        // Resolvemos el stream a partir del repositorio (mock u real).
                        // NOTA: runBlocking es un atajo válido mientras el repositorio es
                        // mock (datos instantáneos en memoria). Si tu fuente real hace
                        // llamadas de red, reemplazar esto por LaunchedEffect + State.
                        val streamUrl = remember(titleId) {
                            runBlocking {
                                val all = repository.getCategories().flatMap { it.items } +
                                    repository.getFeatured()
                                all.firstOrNull { it.id == titleId }?.streamUrl
                                    ?: all.first().streamUrl
                            }
                        }
                        PlayerScreen(streamUrl = streamUrl)
                    }
                }
            }
        }
    }
}
