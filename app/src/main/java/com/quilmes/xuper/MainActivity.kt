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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import androidx.navigation.navArgument
import com.quilmes.xuper.data.AppUpdater
import com.quilmes.xuper.data.ContentRepository
import com.quilmes.xuper.data.DeveloperMode
import com.quilmes.xuper.data.ContentKind
import com.quilmes.xuper.data.M3uRepository
import com.quilmes.xuper.data.M3uSource
import com.quilmes.xuper.data.Title
import com.quilmes.xuper.data.WebSourceRegistry
import com.quilmes.xuper.data.IptvAccountTextParser
import com.quilmes.xuper.data.AdultContentSettings
import com.quilmes.xuper.data.TmdbCatalogService
import com.quilmes.xuper.data.AnimeSites
import com.quilmes.xuper.data.JkanimeService
import com.quilmes.xuper.ui.components.UpdatePrompt
import com.quilmes.xuper.ui.screens.MainScreen
import com.quilmes.xuper.ui.screens.AdultEmbedPlayerScreen
import com.quilmes.xuper.ui.screens.HomePlayerScreen
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
/** assets/listas/: todas las listas M3U. Las de nombre fijo (abajo) son fuentes propias; el resto son "Otros canales para ver". */
private const val LISTS_DIR = "listas"
/** assets/contenido_oculto/: listas que no salen en los menús normales. Cada .m3u se registra sola. (La sección +18 ya NO usa listas: ver AdultCatalogService.) */
private const val MY_LISTS_DIR = "contenido_oculto"
/** Las listas M3U no fijas de assets/listas/ se registran como fuentes explorables. */
private const val OTHER_CHANNELS_DIR = LISTS_DIR

/** Listas que `buildRepository` lee por nombre (se copian de assets/listas/ a filesDir/). No se repiten en "Otros canales". */
private val FIXED_LIST_FILES = listOf(
    "argentina.m3u", "iptv-extra.m3u", "lista-extendida.m3u",
    "mi-lista.m3u", "peliculas-dominio-publico.m3u", "vod-original.m3u",
)

/** Copias exactas/archivos auxiliares que no son listas independientes. index.m3u sí se registra y se puede explorar. */
private val IGNORED_LIST_FILES = setOf("tv-online2.m3u", "tv argentinas.m3u")

/** .m3u/.m3u8 de assets/listas/ que se registran automáticamente como fuentes. */
private fun otherChannelFiles(assets: android.content.res.AssetManager): List<String> =
    (runCatching { assets.list(OTHER_CHANNELS_DIR)?.toList() }.getOrNull().orEmpty())
        .filter { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) }
        .filter { it.lowercase() !in FIXED_LIST_FILES && it.lowercase() !in IGNORED_LIST_FILES }
        .sorted()

private fun buildRepository(cacheDir: File, assets: android.content.res.AssetManager): ContentRepository {
    val builtInSources = listOf(
        // Sección Argentina: SOLO la lista propia (app/src/main/assets/argentina.m3u).
        M3uSource(
            name = "Argentina",
            url = File(cacheDir, "argentina.m3u").toURI().toString(),
            icon = "🇦🇷",
            homeRows = 1,
            priorityNames = listOf("TN", "La Nación", "LN+", "Crónica", "El Trece", "Eltrece", "Telefe", "América", "C5N", "Canal 9", "TV Pública"),
            flattenIntoOneCategory = true,
            enrichMetadata = false,
            maxPerRow = 5000, // la lista propia entra completa: antes se recortaba a 100 canales
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
        // Listas locales que ya venían en el proyecto. Antes se borraban sus copias de filesDir
        // al iniciar, pero no se reinstalaban ni se registraban como fuentes del catálogo.
        M3uSource(
            name = "Canales adicionales",
            url = File(cacheDir, "iptv-extra.m3u").toURI().toString(),
            icon = "🌐",
            homeRows = 1,
            enrichMetadata = false,
            maxPerRow = 80,
            maxRows = 80,
        ),
        M3uSource(
            name = "Lista extendida",
            url = File(cacheDir, "lista-extendida.m3u").toURI().toString(),
            icon = "📡",
            homeRows = 0,
            enrichMetadata = false,
            maxPerRow = 80,
            maxRows = 120,
        ),
        M3uSource(
            name = "Mi lista",
            url = File(cacheDir, "mi-lista.m3u").toURI().toString(),
            icon = "⭐",
            homeRows = 0,
            enrichMetadata = false,
            maxPerRow = 60,
            maxRows = 20,
        ),
        M3uSource(
            name = "Películas de dominio público",
            url = File(cacheDir, "peliculas-dominio-publico.m3u").toURI().toString(),
            icon = "🎞️",
            homeRows = 1,
            contentHint = com.quilmes.xuper.data.ContentKind.MOVIE,
            enrichMetadata = false,
            maxPerRow = 60,
            maxRows = 10,
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
    // Listas ocultas: cualquier .m3u que se suelte en app/src/main/assets/contenido_oculto/ se registra sola.
    // El nombre del archivo es el nombre de la fuente; el tipo (película/serie/canal) se detecta
    // por cada entrada (.mp4 => película, S01E02 => serie, el resto => TV en vivo).
    val myListSources = (runCatching { assets.list(MY_LISTS_DIR)?.toList() }.getOrNull().orEmpty())
        .filter { it.endsWith(".m3u", true) || it.endsWith(".m3u8", true) }
        .sorted()
        .map { file ->
            M3uSource(
                name = file.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Mi lista" },
                url = File(File(cacheDir, MY_LISTS_DIR), file).toURI().toString(),
                icon = "🎬",
                homeRows = 2,
                enrichMetadata = false,
                maxPerRow = 60,
                maxRows = 200,
            )
        }
    // "Otros canales para ver" (pestaña Argentina): los .m3u de assets/listas/ que no son de nombre fijo ni están ignorados.
    // No aparece en Inicio (homeRows = 0); sus canales en vivo se agrupan por group-title abajo de todo en ARGENTINA.
    val otherChannelSources = otherChannelFiles(assets)
        .map { file ->
            M3uSource(
                name = "Otros: " + file.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim(),
                url = File(File(cacheDir, OTHER_CHANNELS_DIR), file).toURI().toString(),
                icon = "📺",
                homeRows = 0,
                enrichMetadata = false,
                maxPerRow = 100_000,
                maxRows = 100_000,
                // index.m3u es un índice mundial enorme: se navega desde el explorador de listas,
                // no se vuelca entero al final de la pantalla Argentina.
                liveOthers = !file.equals("index.m3u", ignoreCase = true),
            )
        }
    return M3uRepository(
        cacheDir = cacheDir,
        // Las cuentas del TXT siempre tienen prioridad sobre las fuentes públicas/integradas.
        sources = accountSources + myListSources + otherChannelSources + loadCustomM3uSources(cacheDir) + builtInSources,
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
    private var appNavController: NavHostController? = null
    private var resetToHomeOnNextResume = false

    override fun onStop() {
        super.onStop()
        // Al volver desde otra app/pantalla del sistema, Xuper arranca de nuevo en Inicio.
        // La limpieza de sesiones de reproductores ocurre al desmontar sus pantallas.
        resetToHomeOnNextResume = true
    }

    override fun onResume() {
        super.onResume()
        // Actualizaciones automáticas: consulta barata (máx. cada 30 min); si hay versión nueva sale el cartel.
        lifecycleScope.launch { AppUpdater.check(applicationContext) }
        if (resetToHomeOnNextResume) {
            resetToHomeOnNextResume = false
            appNavController?.let { nav ->
                runCatching {
                    nav.navigate("main") {
                        popUpTo("main") { inclusive = false }
                        launchSingleTop = true
                    }
                }.onFailure { android.util.Log.w("XuperLifecycle", "No se pudo reiniciar la navegación a Inicio", it) }
            }
        }
    }

    @Volatile private var iptvRefreshStarted = false
    private val localFilesReady = CompletableDeferred<Unit>()

    private fun requestIptvRefresh() {
        if (iptvRefreshStarted) return
        iptvRefreshStarted = true
        lifecycleScope.launch {
            localFilesReady.await()
            runCatching { repository.refresh() }
                .onFailure { android.util.Log.e("XuperQuilmesTV", "No se pudieron cargar las fuentes IPTV", it) }
        }
    }

    private val pendingDetailTitle = mutableStateOf<Title?>(null)
    private val repository: ContentRepository by lazy { buildRepository(filesDir, assets) }
    private val tmdbCatalogService: TmdbCatalogService by lazy { TmdbCatalogService(filesDir) }
    private val jkanimeService: JkanimeService by lazy { JkanimeService(AnimeSites.MAIN_URL) }
    /** null = no hay sitio +18 configurado (AnimeSites.ADULT_URL vacío o inválido): el botón no aparece. */
    private val adultAnimeService: JkanimeService? by lazy {
        AnimeSites.ADULT_URL.trim().takeIf { it.isNotEmpty() }?.let { url ->
            runCatching { JkanimeService(url) }
                .onFailure { android.util.Log.w("XuperAnime", "AnimeSites.ADULT_URL inválida: $url", it) }
                .getOrNull()
        }
    }

    /** Elige el servicio según el host de la URL del episodio (siguiente episodio y cabeceras del video). */
    private fun serviceFor(episodeUrl: String): JkanimeService {
        val host = runCatching { Uri.parse(episodeUrl).host }.getOrNull().orEmpty()
        val mainHost = runCatching { Uri.parse(jkanimeService.base).host }.getOrNull().orEmpty()
        val adult = adultAnimeService
        val adultHost = adult?.let { runCatching { Uri.parse(it.base).host }.getOrNull() }.orEmpty()
        return if (adult != null && !host.equals(mainHost, true) && host.equals(adultHost, true)) adult else jkanimeService
    }


    /** Corre en un hilo de fondo (Dispatchers.IO). */
    private fun prepareLocalFiles() {
        // Conserva los archivos de catálogo y las listas existentes. En versiones anteriores
        // se borraban varias listas locales sin volver a instalarlas, haciendo desaparecer canales.
        // Solo limpiamos la antigua lista de fallos: las URL se reintentan y no deben quedar
        // bloqueadas para siempre después de un error transitorio.
        File(filesDir, "unavailable_streams.txt").delete()
        // Cachés viejos de la sección Argentina (lista pública + agregados): se descartan.
        filesDir.listFiles { f -> f.name.startsWith("catalog_") && f.name.contains("argentina") }?.forEach { it.delete() }
        // Instala/actualiza todas las listas empaquetadas en el APK en el almacenamiento privado.
        // Cada una se registra como fuente en buildRepository(); TMDB solo aporta metadatos.
        // Listas de nombre fijo: se leen de assets/listas/ (si alguna quedó suelta en la raíz de assets/, también sirve).
        for (name in FIXED_LIST_FILES) {
            runCatching {
                val input = runCatching { assets.open("$LISTS_DIR/$name") }.getOrElse { assets.open(name) }
                input.use { src ->
                    File(filesDir, name).outputStream().use { output -> src.copyTo(output) }
                }
            }.onFailure { error ->
                android.util.Log.w("XuperQuilmesTV", "Lista no incluida en el APK: $name (assets/$LISTS_DIR/$name)", error)
            }
        }
        // Contenido oculto (assets/contenido_oculto/*.m3u) y "Otros canales para ver" (assets/listas/*.m3u que no son
        // de nombre fijo ni están ignoradas): se copian a filesDir/<carpeta>/.
        val copies = listOf(
            MY_LISTS_DIR to (runCatching { assets.list(MY_LISTS_DIR)?.toList() }.getOrNull().orEmpty()),
            OTHER_CHANNELS_DIR to otherChannelFiles(assets),
        )
        for ((folder, names) in copies) {
            runCatching {
                val dir = File(filesDir, folder).apply { mkdirs() }
                names.forEach { name ->
                    runCatching {
                        assets.open("$folder/$name").use { input ->
                            File(dir, name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }.onFailure { android.util.Log.e("XuperQuilmesTV", "No se pudo instalar $folder/$name", it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ajuste +18: debe inicializarse antes de que cualquier servicio de TMDb lea include_adult.
        AdultContentSettings.init(applicationContext)
        // Opciones de desarrollador (LAB oculto): leer el estado guardado antes de componer la UI.
        DeveloperMode.init(applicationContext)
        XuperBraveAdblock.initialize(applicationContext)
        AppUpdater.cleanupOldDownloads(applicationContext)

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
                appNavController = navController
                val observedSections by repository.sections.collectAsState()
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            repository = repository,
                            catalogService = tmdbCatalogService,
                            onRequestIptv = { requestIptvRefresh() },
                            onPlay = { id ->
                                val selected = repository.find(id)
                                if (id.startsWith("jkanime-")) {
                                    val saved = runCatching {
                                        val items = org.json.JSONArray(getSharedPreferences("xuper-library", MODE_PRIVATE).getString("recent_items", "[]"))
                                        (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                                            .firstOrNull { it.optString("id") == id }
                                    }.getOrNull()
                                    val savedUrl = saved?.optString("streamUrl").orEmpty()
                                    if (savedUrl.startsWith("https://") || savedUrl.startsWith("http://")) {
                                        val encodedStream = Base64.encodeToString(savedUrl.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                                        val savedName = saved?.optString("name").orEmpty().ifBlank { "Anime" }
                                        navController.navigate("external-player/${Uri.encode(savedName)}/$encodedStream/$id")
                                    }
                                } else if (selected?.isLive == true) navController.navigate("player/$id")
                                else navController.navigate("detail/$id")
                            },
                            jkanimeService = jkanimeService,
                            adultAnimeService = adultAnimeService,
                            onPlayAnimeEpisode = { title, streamUrl, episodeUrl ->
                                val encodedStream = Base64.encodeToString(
                                    streamUrl.toByteArray(StandardCharsets.UTF_8),
                                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                                )
                                val encodedEpisode = Base64.encodeToString(
                                    episodeUrl.toByteArray(StandardCharsets.UTF_8),
                                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                                )
                                val key = "jkanime-$encodedEpisode"
                                navController.navigate("external-player/${Uri.encode(title)}/$encodedStream/$key")
                            },
                            // Inicio: un solo reproductor nativo. VideoAPI aporta el video y CineSrc los subtítulos.
                            onPlayHomeTitle = { titleName, catalogId ->
                                navController.navigate("home-player/${Uri.encode(titleName)}/${Uri.encode(catalogId)}")
                            },
                            // +18: reproductor oficial embebido (no pasa por "Continuar viendo").
                            onPlayAdultVideo = { viewKey, videoTitle ->
                                navController.navigate("adult-player/${Uri.encode(viewKey)}/${Uri.encode(videoTitle)}")
                            },
                        )
                    }
                    composable(
                        route = "adult-player/{adultKey}/{adultTitle}",
                        arguments = listOf(
                            navArgument("adultKey") { type = NavType.StringType },
                            navArgument("adultTitle") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        AdultEmbedPlayerScreen(
                            viewKey = entry.arguments?.getString("adultKey").orEmpty(),
                            title = entry.arguments?.getString("adultTitle").orEmpty(),
                            onExit = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "home-player/{homeName}/{homeId}",
                        arguments = listOf(
                            navArgument("homeName") { type = NavType.StringType },
                            navArgument("homeId") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        HomePlayerScreen(
                            name = entry.arguments?.getString("homeName").orEmpty(),
                            catalogId = entry.arguments?.getString("homeId").orEmpty(),
                            catalogService = tmdbCatalogService,
                            onPlayNext = { nextName, nextId ->
                                navController.popBackStack()
                                navController.navigate("home-player/${Uri.encode(nextName)}/${Uri.encode(nextId)}")
                            },
                            onExit = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = "detail/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        val title = remember(id, pendingDetailTitle.value?.id, observedSections) {
                            repository.find(id) ?: pendingDetailTitle.value?.takeIf { it.id == id }
                        }
                        if (title != null) DetailScreen(
                            title = title,
                            catalogService = tmdbCatalogService,
                            onPlay = { request ->
                              val useVideoApi = request.startsWith("videoapi:")
                              val useVidly = request.startsWith("vidly:")
                              val useVidLux = request.startsWith("vidlux:")
                              val usePrueba = request.startsWith("prueba:")
                              val useSimuladores = request.startsWith("simuladores:")
                              val requestedId = when {
                                  useVideoApi -> request.removePrefix("videoapi:")
                                  useVidly -> request.removePrefix("vidly:")
                                  useVidLux -> request.removePrefix("vidlux:")
                                  usePrueba -> request.removePrefix("prueba:")
                                  useSimuladores -> request.removePrefix("simuladores:")
                                  else -> request
                              }
                              val url = when {
                                  useVideoApi -> WebSourceRegistry.videoApiUrl(requestedId)
                                  useVidly -> WebSourceRegistry.vidlyUrl(requestedId)
                                  useVidLux -> WebSourceRegistry.vidLuxUrl(requestedId)
                                  usePrueba -> WebSourceRegistry.videoApiPruebaUrl(requestedId)
                                  useSimuladores -> Regex("^.*\\|s(\\d+)\\|e(\\d+)$").matchEntire(requestedId)?.let { match ->
                                      val season = match.groupValues[1].toIntOrNull() ?: 1
                                      val episode = match.groupValues[2].toIntOrNull() ?: 1
                                      val videoId = simuladoresVideoId(season, episode)
                                      if (videoId != null) "https://ok.ru/videoembed/$videoId" else null
                                  }
                                  else -> WebSourceRegistry.vidlyUrl(requestedId)
                              }
                              if (url != null) {
                                  val encodedUrl = Base64.encodeToString(url.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                                  navController.navigate("external-player/${Uri.encode(title.name)}/$encodedUrl/${Uri.encode(requestedId)}")
                              }
                            },
                            onOpenTitle = { relatedTitle ->
                                pendingDetailTitle.value = relatedTitle
                                navController.navigate("detail/${relatedTitle.id}")
                            },
                        )
                    }
                    composable(
                        route = "external-player/{streamName}/{streamUrl}/{contentKey}",
                        arguments = listOf(
                            navArgument("streamName") { type = NavType.StringType },
                            navArgument("streamUrl") { type = NavType.StringType },
                            navArgument("contentKey") { type = NavType.StringType },
                        ),
                    ) { entry ->
                        val streamName = entry.arguments?.getString("streamName").orEmpty()
                        val encodedUrl = entry.arguments?.getString("streamUrl").orEmpty()
                        val contentKey = entry.arguments?.getString("contentKey").orEmpty()
                        val streamUrl = runCatching {
                            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), StandardCharsets.UTF_8)
                        }.getOrDefault("")
                        if (streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) {
                            // Registrar también los embeds (Vidly/Latino/VideoAPI) en la misma
                            // biblioteca que consume la sección "Continuar viendo".
                            LaunchedEffect(contentKey, streamUrl) {
                                if (contentKey.isNotBlank()) {
                                    val prefs = getSharedPreferences("xuper-library", MODE_PRIVATE)
                                    val recent = prefs.getString("recent", "").orEmpty().split("|")
                                        .filter { it.isNotBlank() && it != contentKey }.toMutableList()
                                    recent.add(contentKey)
                                    val episodeMatch = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(contentKey)
                                    val kind = if (episodeMatch != null || contentKey.startsWith("jkanime-")) ContentKind.SERIES.name else ContentKind.MOVIE.name
                                    val recentJson = org.json.JSONObject().apply {
                                        put("id", contentKey)
                                        put("name", streamName)
                                        put("description", "")
                                        put("posterUrl", "")
                                        put("backdropUrl", "")
                                        put("streamUrl", streamUrl)
                                        put("isLive", false)
                                        put("year", "")
                                        put("rating", "")
                                        put("contentKind", kind)
                                    }
                                    val items = runCatching { org.json.JSONArray(prefs.getString("recent_items", "[]")) }
                                        .getOrDefault(org.json.JSONArray())
                                    val rebuilt = org.json.JSONArray().apply {
                                        put(recentJson)
                                        for (i in 0 until items.length()) {
                                            val obj = items.optJSONObject(i) ?: continue
                                            if (obj.optString("id") != contentKey && length() < 29) put(obj)
                                        }
                                    }
                                    prefs.edit().putString("recent", recent.takeLast(30).joinToString("|"))
                                        .putString("recent_items", rebuilt.toString()).apply()
                                }
                            }
                            var nextExternalEpisode by remember(contentKey) { mutableStateOf<Title?>(null) }
                            LaunchedEffect(contentKey, streamUrl) {
                                nextExternalEpisode = null
                                if (contentKey.startsWith("jkanime-")) {
                                    val episodeUrl = runCatching {
                                        String(
                                            Base64.decode(contentKey.removePrefix("jkanime-"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                                            StandardCharsets.UTF_8,
                                        )
                                    }.getOrDefault("")
                                    val episodeNumber = runCatching {
                                        Uri.parse(episodeUrl).pathSegments.lastOrNull()?.toIntOrNull()
                                    }.getOrNull()
                                    val animeSlug = runCatching {
                                        Uri.parse(episodeUrl).pathSegments.firstOrNull().orEmpty()
                                    }.getOrDefault("")
                                    if (episodeUrl.isNotBlank() && episodeNumber != null && animeSlug.isNotBlank()) {
                                        runCatching {
                                            val episodeService = serviceFor(episodeUrl)
                                            val animeInfo = episodeService.getAnimeInfo("${episodeService.base}/$animeSlug/")
                                            val pageNumber = ((episodeNumber - 1) / 16) + 1
                                            val page = episodeService.getEpisodesPage(animeInfo.url, pageNumber)
                                            var nextEpisodeData = page.episodes.firstOrNull { it.number > episodeNumber }
                                            if (nextEpisodeData == null && page.totalPages > page.page) {
                                                nextEpisodeData = episodeService.getEpisodesPage(animeInfo.url, page.page + 1).episodes.minByOrNull { it.number }
                                            }
                                            val nextEpisode = nextEpisodeData ?: return@runCatching
                                            val nextStream = episodeService.getEpisodeStream(nextEpisode.url)
                                            val nextKey = "jkanime-" + Base64.encodeToString(
                                                nextEpisode.url.toByteArray(StandardCharsets.UTF_8),
                                                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                                            )
                                            nextExternalEpisode = Title(
                                                id = nextKey,
                                                name = "${animeInfo.title} · ${nextEpisode.title}",
                                                description = animeInfo.description,
                                                posterUrl = animeInfo.image,
                                                backdropUrl = animeInfo.image,
                                                streamUrl = nextStream.streamUrl,
                                                contentKind = ContentKind.SERIES,
                                            )
                                        }.onFailure { error ->
                                            android.util.Log.w("XuperAnime", "No se pudo preparar el siguiente episodio", error)
                                        }
                                    }
                                    return@LaunchedEffect
                                }
                                val match = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(contentKey)
                                if (match != null) {
                                    val (tmdbNumber, seasonText, episodeText) = match.destructured
                                    val seriesId = "tmdb-tv-$tmdbNumber"
                                    val currentSeason = seasonText.toIntOrNull() ?: 0
                                    val currentEpisode = episodeText.toIntOrNull() ?: 0
                                    if (tmdbNumber == "332") {
                                        val nextSeason = if (currentSeason == 1 && currentEpisode >= 13) 2 else currentSeason
                                        val nextEpisodeNumber = if (currentSeason == 1 && currentEpisode >= 13) 1 else currentEpisode + 1
                                        val nextVideoId = simuladoresVideoId(nextSeason, nextEpisodeNumber)
                                        if (nextVideoId != null) {
                                            val nextKey = "tmdb-tv-332|s$nextSeason|e$nextEpisodeNumber"
                                            val nextUrl = "https://ok.ru/videoembed/$nextVideoId"
                                            nextExternalEpisode = Title(
                                                id = nextKey,
                                                name = "Los Simuladores · T$nextSeason E$nextEpisodeNumber",
                                                description = "",
                                                posterUrl = "",
                                                backdropUrl = "",
                                                streamUrl = nextUrl,
                                                contentKind = ContentKind.SERIES,
                                            )
                                        }
                                        return@LaunchedEffect
                                    }
                                    val seasons = tmdbCatalogService.loadSeasons(seriesId).filter { it.number > 0 }.sortedBy { it.number }
                                    val sameSeasonEpisodes = tmdbCatalogService.loadEpisodes(seriesId, currentSeason).sortedBy { it.number }
                                    var nextSeasonNumber = currentSeason
                                    var nextEpisodeData = sameSeasonEpisodes.firstOrNull { it.number > currentEpisode }
                                    var nextSeasonPoster = seasons.firstOrNull { it.number == currentSeason }?.posterUrl.orEmpty()
                                    if (nextEpisodeData == null) {
                                        val followingSeason = seasons.firstOrNull { it.number > currentSeason }
                                        if (followingSeason != null) {
                                            nextSeasonNumber = followingSeason.number
                                            nextSeasonPoster = followingSeason.posterUrl
                                            nextEpisodeData = tmdbCatalogService.loadEpisodes(seriesId, nextSeasonNumber).minByOrNull { it.number }
                                        }
                                    }
                                    val nextEpisode = nextEpisodeData ?: return@LaunchedEffect
                                    val nextKey = "$seriesId|s$nextSeasonNumber|e${nextEpisode.number}"
                                    val nextUrl = if (tmdbNumber == "332") {
                                        val okId = simuladoresVideoId(nextSeasonNumber, nextEpisode.number)
                                        okId?.let { "https://ok.ru/videoembed/$it" }
                                    } else if (WebSourceRegistry.isVideoApiPruebaUrl(streamUrl)) {
                                        WebSourceRegistry.videoApiPruebaUrl(nextKey)
                                    } else if (runCatching { WebSourceRegistry.isVideoApiHost(Uri.parse(streamUrl).host) }.getOrDefault(false)) {
                                        WebSourceRegistry.videoApiUrl(nextKey)
                                    } else if (runCatching { WebSourceRegistry.isVidlyHost(Uri.parse(streamUrl).host) }.getOrDefault(false)) {
                                        WebSourceRegistry.vidlyUrl(nextKey)
                                    } else if (runCatching { WebSourceRegistry.isCineSrcHost(Uri.parse(streamUrl).host) }.getOrDefault(false)) {
                                        // LAB: el siguiente episodio conserva CineSrc.
                                        WebSourceRegistry.cineSrcUrl(nextKey)
                                    } else {
                                        null
                                    }
                                    if (!nextUrl.isNullOrBlank()) {
                                        nextExternalEpisode = Title(
                                            id = nextKey,
                                            name = nextEpisode.name,
                                            description = nextEpisode.overview,
                                            posterUrl = nextEpisode.stillUrl.ifBlank { nextSeasonPoster },
                                            backdropUrl = nextEpisode.stillUrl.ifBlank { nextSeasonPoster },
                                            streamUrl = nextUrl,
                                            contentKind = ContentKind.SERIES,
                                        )
                                    }
                                }
                            }
                            val animeEpisodeUrl = if (contentKey.startsWith("jkanime-")) {
                                runCatching {
                                    String(
                                        Base64.decode(contentKey.removePrefix("jkanime-"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                                        StandardCharsets.UTF_8,
                                    )
                                }.getOrDefault("")
                            } else ""
                            val animeHeaders = if (animeEpisodeUrl.isNotBlank()) mapOf(
                                "Referer" to animeEpisodeUrl,
                                "Origin" to serviceFor(animeEpisodeUrl).base,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            ) else emptyMap()
                            PlayerScreen(
                                name = streamName,
                                streamUrl = streamUrl,
                                streamHeaders = animeHeaders,
                                currentTitleId = contentKey,
                                nextEpisode = nextExternalEpisode,
                                onPlayNextEpisode = { nextId ->
                                    val next = nextExternalEpisode?.takeIf { it.id == nextId }
                                    if (next != null) {
                                        val encodedNextUrl = Base64.encodeToString(next.streamUrl.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                                        val episodeMatch = Regex("^tmdb-tv-(\\d+)\\|s(\\d+)\\|e(\\d+)$").matchEntire(next.id)
                                        val episodeLabel = if (episodeMatch != null) {
                                            val (_, season, episode) = episodeMatch.destructured
                                            "${next.name} · T$season E$episode"
                                        } else next.name
                                        navController.popBackStack()
                                        navController.navigate("external-player/${Uri.encode(episodeLabel)}/$encodedNextUrl/${Uri.encode(next.id)}")
                                    }
                                },
                                onExit = { navController.popBackStack() },
                            )
                        }
                    }
                    composable(
                        route = "player/{titleId}",
                        arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("titleId").orEmpty()
                        // Congelamos la fuente elegida al entrar al reproductor: cuando llegan
                        // alternativas nuevas, no queremos cambiar/reiniciar el video actual.
                        val title = remember(id) { repository.find(id) }
                        if (title != null && title.streamUrl.isNotBlank()) {
                            LaunchedEffect(id) {
                                if (!title.isLive) {
                                    val prefs = getSharedPreferences("xuper-library", MODE_PRIVATE)
                                    val recent = prefs.getString("recent", "").orEmpty().split("|").filter { it.isNotBlank() && it != id }.toMutableList()
                                    recent.add(id)
                                    val recentJson = org.json.JSONObject().apply {
                                        put("id", title.id); put("name", title.name); put("description", title.description)
                                        put("posterUrl", title.posterUrl); put("backdropUrl", title.backdropUrl)
                                        put("streamUrl", title.streamUrl); put("isLive", title.isLive)
                                        put("year", title.year ?: ""); put("rating", title.rating ?: "")
                                        put("contentKind", title.contentKind.name)
                                    }
                                    val items = runCatching { org.json.JSONArray(prefs.getString("recent_items", "[]")) }.getOrDefault(org.json.JSONArray())
                                    val rebuilt = org.json.JSONArray().apply {
                                        put(recentJson)
                                        for (i in 0 until items.length()) {
                                            val obj = items.optJSONObject(i) ?: continue
                                            if (obj.optString("id") != title.id && length() < 29) put(obj)
                                        }
                                    }
                                    prefs.edit().putString("recent", recent.takeLast(30).joinToString("|"))
                                        .putString("recent_items", rebuilt.toString()).apply()
                                }
                            }
                            val m3u = repository as? M3uRepository
                            // La lista cambia mientras se descargan fuentes en segundo plano;
                            // el reproductor recibe las alternativas nuevas sin reiniciar el video.
                            val alternatePlayers = remember(id, observedSections) { m3u?.getPlayableAlternatives(title).orEmpty() }
                            // Cada reproducción empieza por la primera cuenta configurada que tenga
                            // una coincidencia; el reproductor solo prueba las demás si esta falla.
                            val preferredPlayback = remember(id, title.streamUrl) {
                                alternatePlayers.firstOrNull { it.streamUrl == title.streamUrl } ?: title
                            }
                            val playbackId = preferredPlayback.id
                            // Si se tocó un canal en la pestaña Argentina, el zapping usa esa misma lista (el canal de al lado);
                            // si no, la cola de siempre.
                            val channelQueue = remember(playbackId) {
                                com.quilmes.xuper.data.ChannelQueueStore.queueFor(playbackId)
                                    ?: m3u?.getChannelQueue(playbackId).orEmpty()
                            }
                            val nextEpisode = remember(playbackId) { m3u?.getNextEpisode(playbackId) }
                            // Canales relacionados (principal + secundarios armados en ARGENTINA): el hub del reproductor
                            // los ofrece como "Elegir canal secundario".
                            val relatedChannels = remember(playbackId, observedSections) {
                                if (preferredPlayback.isLive)
                                    com.quilmes.xuper.data.ChannelRelations.groupFor(this@MainActivity, observedSections, preferredPlayback.streamUrl)
                                else emptyList()
                            }
                            PlayerScreen(
                                name = title.name,
                                streamUrl = preferredPlayback.streamUrl,
                                streamHeaders = preferredPlayback.requestHeaders,
                                channelQueue = channelQueue,
                                currentTitleId = playbackId,
                                nextEpisode = nextEpisode,
                                alternatePlayers = alternatePlayers,
                                relatedChannels = relatedChannels,
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
                        }
                    }
                }
                // Cartel de actualización automática (no dibuja nada si no hay versión nueva).
                UpdatePrompt()
            }
        }
    }
}
private fun simuladoresVideoId(season: Int, episode: Int): String? {
    val ids = listOf(
        listOf("7286191491722", "9601507658378", "7286192802442", "7286192605834", "7286192671370", "9601509427850", "7286192736906", "9601510673034", "9601512311434", "9601513818762", "7286192474762", "7286193130122", "9601515719306"),
        listOf("7289262312074", "9602281245322", "9602281310858", "7289262181002", "9602281179786", "7289262246538", "9602281114250", "7289262508682", "7289262115466", "9602281376394", "7289262574218")
    )
    return ids.getOrNull(season - 1)?.getOrNull(episode - 1)
}

