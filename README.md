# Xuper Quilmes TV — Android TV

Aplicación personal de streaming para Android TV, escrita en Kotlin + Jetpack Compose for TV. El proyecto separa el catálogo visual, los streams IPTV en vivo y las fuentes web alternativas.

## Experiencia actual

- **Inicio:** catálogo visual estilo streaming con tendencias, películas y series populares, y filas por plataforma en Argentina. La información, las imágenes, las temporadas y las sinopsis vienen de TMDB. Una ficha de TMDB no se considera un vídeo reproducible por sí misma.
- **Argentina:** todos los canales IPTV en vivo, incluidos los canales deportivos y señales argentinas, están en la sección ARGENTINA de la barra lateral; no se duplican en Inicio.
- **Buscar:** búsqueda de películas y series en TMDB y el catálogo local.
- **Mi lista / Continuar viendo / Perfil:** utilidades locales de la aplicación.
- **Ficha de una serie:** selector de temporadas, lista de episodios con título, sinopsis, duración e imagen cuando está disponible. El usuario elige el episodio concreto antes de abrir sus fuentes.
- **Fuentes web alternativas globales:** películas generan el embed de StreamIMDB usando el IMDb ID real consultado en TMDB (`/embed/movie/{imdb_id}/`), más búsqueda en Movies2Watch y búsqueda/catálogo de RePelisHD. Movies2Watch usa rutas internas con slug e identificador propio; Xuper genera una URL candidata de búsqueda (`/?s=...`) a partir del título y no inventa IDs internos. Esa ruta de búsqueda debe verificarse contra el sitio activo. Episodios generan las dos variantes de FrameXTV (`/watch/{TMDB_ID}/{temporada}/{episodio}` y el orden inverso) y búsqueda de Movies2Watch y búsqueda/catálogo de RePelisHD. Se muestran botones Reproducir, sin exigir pegar URLs con el mando. Las rutas de FrameXTV y RePelisHD son candidatas y no garantizan que el proveedor encuentre o reproduzca ese título.
- **IPTV:** las URL M3U8/MP4 y otros medios directos se reproducen con Media3/ExoPlayer. Las páginas web se abren dentro de WebView, con filtro publicitario local compartido.

## Fuentes web y límites

`data/WebSourceRegistry.kt` centraliza la generación de fuentes. Para películas se utiliza la clave de la ficha y el IMDb ID real que devuelve `TmdbCatalogService.loadImdbId`; el servicio consulta `/{movie|tv}/{tmdb_id}/external_ids` de TMDB y cachea los IDs reales durante 180 días (los resultados sin ID durante 7 días), sin derivarlos ni inventarlos. Para episodios, la clave es `ID_SERIE|sTEMPORADA|eEPISODIO`. Movies2Watch se incorpora como fuente global con búsqueda candidata basada en el título (`https://movies2watch.vc/?s=...`); las fichas observadas usan slugs e IDs propios, así que Xuper no puede construir una ficha específica sin resolver primero el resultado del catálogo. La ruta `/?s=` aún debe probarse en Android TV. La URL que dio el usuario para FrameXTV fue `https://framextv.xyz/watch/1396/3/11`; Xuper prueba esa estructura y también la variante temporada/episodio invertida, porque un único ejemplo no confirma el patrón universal. RePelisHD recibe una búsqueda de título con temporada/capítulo, además de enlaces a sus catálogos. Los botones Reproducir abren cada candidata dentro de la app; no hace falta escribir ni copiar URLs con el control remoto.

**Estado de verificación:** la plantilla StreamIMDB se construye según el formato explícito que dio el usuario (`/embed/movie/{IMDb_ID}/`) y solo se genera si TMDB devuelve un identificador `tt...`. Las rutas de FrameXTV y la búsqueda de RePelisHD son candidatas; no se ha verificado que cada una resuelva todos los títulos ni que todos sus reproductores permitan reproducción en WebView. Las páginas `lewblivehdplay.ru` y `qqwebplay.xyz` aparecen como cabeceras Referer en una lista IPTV aportada por el usuario; no se tratan como catálogos VOD. El parser M3U conserva las cabeceras HTTP que cada stream necesita.

Los proveedores cambian su estructura y pueden requerir JavaScript, cookies, acceso regional o mecanismos incompatibles con WebView. No se inventan URLs de vídeo directas ni se considera una fuente válida solo por guardarla. Usar únicamente fuentes a las que se tenga acceso legítimo.

## Protección publicitaria basada en Brave

`ui/screens/XuperBraveAdblock.kt` integra `io.github.mlm-games:adblock:0.1.2`, un wrapper JNI para el motor Rust `adblock-rust` que usa Brave. Intercepta solicitudes del WebView con reglas de red, inyecta CSS/scriptlets cosméticos cuando hay recursos disponibles y conserva `XuperWebRequestFilter.kt` como respaldo.

En segundo plano intenta descargar y cachear EasyList, EasyPrivacy y AdGuard Annoyances; las reglas almacenadas se reutilizan cuando no hay conexión. La interfaz no espera estas descargas. El motor usa licencia MPL-2.0; ver https://github.com/brave/adblock-rust y https://github.com/mlm-games/adblock-rs-kt-wrapper.

Esto **no convierte WebView en Chromium/Brave completo ni garantiza eliminar todos los anuncios**. No puede quitar publicidad integrada directamente en el vídeo/stream o servida desde el mismo origen de forma indistinguible, y algunos filtros cosméticos pueden interferir con reproductores. Las ventanas emergentes, esquemas externos y contenido mixto inseguro siguen restringidos por WebView.

## Catálogo y TMDB

`data/TmdbCatalogService.kt` consulta TMDB para metadatos, imágenes, tendencias, catálogos por plataforma, temporadas y episodios. TMDB no proporciona las URL de reproducción de películas o episodios. Se requiere `TMDB_API_KEY` en `local.properties` para cargar el catálogo en línea. El uso de TMDB está sujeto a sus condiciones y la aplicación no está respaldada ni certificada por TMDB.

El Inicio se construye en `ui/screens/HomeScreen.kt` y solo usa las categorías del catálogo TMDB. Las listas M3U antiguas de películas/series no se mezclan en Inicio, evitando que títulos de dominio público antiguos parezcan estrenos o disponibilidad actual. Las listas IPTV se configuran en `MainActivity.kt`.

## Arquitectura

- `MainActivity.kt`: navegación, carga inicial y fuentes M3U.
- `data/M3uRepository.kt`: carga, indexación y búsqueda de canales/entradas M3U.
- `data/TmdbCatalogService.kt`: metadatos TMDB y temporadas/episodios.
- `data/WebSourceRegistry.kt`: generación de candidatos globales StreamIMDB, FrameXTV, Movies2Watch y RePelisHD por película/episodio, más URL guardadas localmente.
- `data/TmdbCatalogService.kt`: consulta de temporadas, episodios y IMDb IDs reales para construir enlaces StreamIMDB.
- `data/WebPageMediaResolver.kt`: extracción conservadora de medios declarados en HTML inicial; no ejecuta JavaScript ni elude DRM/autenticación.
- `ui/screens/XuperBraveAdblock.kt`: motor adblock-rust, actualización/caché de listas, intercepción de red y filtros cosméticos.
- `ui/screens/HomeScreen.kt`: Inicio tipo streaming sin IPTV argentino ni listas M3U antiguas.
- `ui/screens/DetailScreen.kt`: ficha, temporadas, episodios, sinopsis y gestión de fuentes web.
- `ui/screens/PlayerScreen.kt`: ExoPlayer para medios directos y WebView para páginas web.
- `ui/screens/XuperWebRequestFilter.kt`: reglas de bloqueo web.

## Compilar

Requisitos: Android Studio, **JDK 21**, Android SDK Platform **36** y Gradle **8.11.1 o superior**. `compileSdk = 36` se usa junto con `io.github.mlm-games:adblock:0.1.2` para evitar la exigencia de API 37 introducida por la versión 0.1.3, que no está disponible en los repositorios SDK del runner actual; `targetSdk = 34` y `minSdk = 26` se mantienen sin cambios. El workflow de GitHub Actions configura explícitamente JDK 21 y Gradle 8.11.1 (AGP 8.10.1 rechaza Gradle 8.7). La integración JNI de adblock-rust requiere Android **8.0 / API 26 o superior**. Este repositorio no incluye `gradlew`; abrir la carpeta raíz en Android Studio y sincronizar Gradle. Configurar `TMDB_API_KEY` en `local.properties` antes de compilar. La primera sincronización necesita acceso a Maven Central para descargar el wrapper nativo.

El ZIP de trabajo contiene configuración local y una clave de firma personal. No publicar ni compartir públicamente esos archivos. Si se publica el proyecto, quitar `local.properties`, reemplazar la clave y sus contraseñas y no subir secretos al control de versiones.

## Notas de implementación

- Mantener el sistema de fuentes web alternativas simple y separado del reproductor IPTV.
- No poner canales argentinos en Inicio; viven en ARGENTINA.
- No volver a mostrar listas M3U antiguas de películas/series en Inicio.
- En series, conservar el selector de temporada y el título/sinopsis de cada episodio.
- No presentar un póster de TMDB como reproducción garantizada: si no hay stream directo, ofrecer ficha y fuentes alternativas guardadas.
- Las URL generadas por plantilla son candidatas no verificadas. No etiquetar un sitio como fuente funcional hasta probarlo.
- No exigir pegar enlaces con el control remoto: generar fuentes desde IMDb ID de TMDB, título, temporada y episodio; mostrar botones Reproducir por proveedor.
- El motor Brave se integra en WebView; no abrir un navegador externo ni incorporar las funciones generales de Brave.
- Los controles del reproductor deben mantener el comportamiento del mando descrito en el código existente.
- Si un sitio muestra anuncios, registrar el dominio/patrón concreto antes de añadir una regla; bloquear agresivamente un dominio completo puede romper el vídeo.


## Nota sobre el filtro publicitario

Para que el proyecto compile con Android SDK 36 y repositorios Maven estándar, se retiró la dependencia `io.github.mlm-games:adblock`: la versión 0.1.2 no está publicada en los repositorios configurados y la 0.1.3 exige compileSdk 37. Xuper conserva el filtro local de dominios y URL en WebView; ya no incluye el motor nativo Brave/adblock-rust ni sus listas cosméticas.
