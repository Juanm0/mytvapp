# Xuper Quilmes TV — Android TV

Aplicación personal de streaming para Android TV, escrita en Kotlin + Jetpack Compose for TV. El proyecto separa el catálogo visual, los streams IPTV en vivo y las fuentes web alternativas.

## Experiencia actual

- **Inicio:** catálogo visual estilo streaming con tendencias, películas y series populares, y filas por plataforma en Argentina. La información, las imágenes, las temporadas y las sinopsis vienen de TMDB. Una ficha de TMDB no se considera un vídeo reproducible por sí misma.
- **Argentina:** todos los canales IPTV en vivo, incluidos los canales deportivos y señales argentinas, están en la sección ARGENTINA de la barra lateral; no se duplican en Inicio.
- **Buscar:** búsqueda de películas y series en TMDB y el catálogo local.
- **Mi lista / Continuar viendo / Perfil:** utilidades locales de la aplicación.
- **Ficha de una serie:** selector de temporadas, lista de episodios con título, sinopsis, duración e imagen cuando está disponible. El usuario elige el episodio concreto antes de abrir sus fuentes.
- **Fuentes web alternativas:** permite guardar más de una URL para una película o para cada episodio. Las URLs se almacenan localmente y pueden probarse o quitarse. Una URL guardada es una candidata, no una garantía de reproducción.
- **IPTV:** las URL M3U8/MP4 y otros medios directos se reproducen con Media3/ExoPlayer. Las páginas web se abren en WebView para utilizar el reproductor que ofrece el propio sitio.

## Fuentes web y límites

`data/WebSourceRegistry.kt` centraliza las URL alternativas. Para películas se utiliza la clave de la ficha; para episodios, `ID_SERIE|sTEMPORADA|eEPISODIO`. El ejemplo FrameXTV se guarda solo para *Breaking Bad*, temporada 3, episodio 11:

`https://framextv.xyz/watch/1396/3/11`

No debe asumirse que ese enlace sirve para otras series o episodios. Para añadir una fuente desde la ficha, seleccionar el episodio (en series), pegar una URL HTTP/HTTPS y pulsar **Agregar URL**. Las fuentes se guardan en SharedPreferences del dispositivo.

El proyecto no descubre mágicamente nuevas URL ni verifica por adelantado todos los reproductores. Los proveedores web cambian su estructura, pueden requerir JavaScript, cookies, acceso regional o mecanismos incompatibles con WebView. No se inventan enlaces ni se considera una fuente válida solo por guardarla. Usar únicamente fuentes a las que se tenga acceso legítimo.

## Bloqueador web

`ui/screens/XuperWebRequestFilter.kt` bloquea un conjunto local de dominios publicitarios y rastreadores, además de patrones de URL. El WebView deshabilita nuevas ventanas, rechaza esquemas externos, bloquea contenido mixto inseguro y aplica reglas cosméticas best-effort a banners/capas comunes.

**Esto no es Brave Shields ni uBlock Origin.** No garantiza bloquear anuncios servidos desde el mismo dominio que el vídeo, anuncios insertados dentro del stream, anuncios first-party, redirecciones que se ejecutan dentro de scripts ofuscados o todos los cambios de cada sitio. Las reglas cosméticas pueden fallar o interferir con algún reproductor. No se bloquean globalmente todos los iframes ni los elementos de vídeo porque eso rompería contenido legítimo.

## Catálogo y TMDB

`data/TmdbCatalogService.kt` consulta TMDB para metadatos, imágenes, tendencias, catálogos por plataforma, temporadas y episodios. TMDB no proporciona las URL de reproducción de películas o episodios. Se requiere `TMDB_API_KEY` en `local.properties` para cargar el catálogo en línea. El uso de TMDB está sujeto a sus condiciones y la aplicación no está respaldada ni certificada por TMDB.

El Inicio se construye en `ui/screens/HomeScreen.kt` y solo usa las categorías del catálogo TMDB. Las listas M3U antiguas de películas/series no se mezclan en Inicio, evitando que títulos de dominio público antiguos parezcan estrenos o disponibilidad actual. Las listas IPTV se configuran en `MainActivity.kt`.

## Arquitectura

- `MainActivity.kt`: navegación, carga inicial y fuentes M3U.
- `data/M3uRepository.kt`: carga, indexación y búsqueda de canales/entradas M3U.
- `data/TmdbCatalogService.kt`: metadatos TMDB y temporadas/episodios.
- `data/WebSourceRegistry.kt`: registro global de URL alternativas por título/episodio.
- `data/WebPageMediaResolver.kt`: extracción conservadora de medios declarados en HTML inicial; no ejecuta JavaScript ni elude DRM/autenticación.
- `ui/screens/HomeScreen.kt`: Inicio tipo streaming sin IPTV argentino ni listas M3U antiguas.
- `ui/screens/DetailScreen.kt`: ficha, temporadas, episodios, sinopsis y gestión de fuentes web.
- `ui/screens/PlayerScreen.kt`: ExoPlayer para medios directos y WebView para páginas web.
- `ui/screens/XuperWebRequestFilter.kt`: reglas de bloqueo web.

## Compilar

Requisitos: Android Studio, JDK 17 y Android SDK 34. Este repositorio no incluye `gradlew`; abrir la carpeta raíz en Android Studio y sincronizar Gradle. Configurar `TMDB_API_KEY` en `local.properties` antes de compilar.

El ZIP de trabajo contiene configuración local y una clave de firma personal. No publicar ni compartir públicamente esos archivos. Si se publica el proyecto, quitar `local.properties`, reemplazar la clave y sus contraseñas y no subir secretos al control de versiones.

## Notas de implementación

- Mantener el sistema de fuentes web alternativas simple y separado del reproductor IPTV.
- No poner canales argentinos en Inicio; viven en ARGENTINA.
- No volver a mostrar listas M3U antiguas de películas/series en Inicio.
- En series, conservar el selector de temporada y el título/sinopsis de cada episodio.
- No presentar un póster de TMDB como reproducción garantizada: si no hay stream directo, ofrecer ficha y fuentes alternativas guardadas.
- Los controles del reproductor deben mantener el comportamiento del mando descrito en el código existente.
- Si un sitio muestra anuncios, registrar el dominio/patrón concreto antes de añadir una regla; bloquear agresivamente un dominio completo puede romper el vídeo.
