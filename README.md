# Xuper — documentación única para futuras IAs

> **Este archivo es la fuente de verdad del proyecto.** No reconstruir decisiones a partir de documentación antigua: los Markdown anteriores se consolidaron y eliminaron porque estaban desactualizados. Antes de cambiar código, inspeccionar el código actual y actualizar este README cuando cambie el comportamiento.

## Objetivo del proyecto

Xuper es una aplicación Android orientada a TV, desarrollada principalmente en Kotlin y Jetpack Compose. Usa TMDB para catálogo, fichas, temporadas, episodios y metadatos; integra reproducción web dentro de la app y también contempla fuentes IPTV/M3U. La experiencia debe funcionar con mando a distancia, mantener el vídeo dentro de Xuper cuando la fuente lo permita y mostrar errores reales en lugar de quedarse cargando indefinidamente.

## Orden y nombres oficiales de reproductores

En la ficha de películas y en cada episodio, las opciones oficiales son, en este orden:

1. **Reproducir** — Vidly (`vidly.pro`). Es el reproductor principal y reemplaza el antiguo botón llamado **Prueba**.
2. **Latino** — VideoAPI (`videoapi.la`; para rutas de anime también puede usarse `videoapp.zip`).
3. **PlayerLab** — reproductor de pruebas por torrent (solo **películas** con ID de TMDB; ver sección "PlayerLab"). Reemplazó al antiguo botón **Alternativo**.

**FrameXTV (`framextv.cc` / `framextv.xyz`, botón "Alternativo") está eliminado** por pedido del usuario: no quedan rutas, hub, selector de proveedor/pistas ni funciones suyas. No volver a agregarlo salvo pedido explícito.

**Prueba 2 / Vidzy (`vidzy.org`) está eliminado** como opción de producto. No volver a agregarlo salvo pedido explícito del usuario. Las rutas, funciones y referencias a ese reproductor deben permanecer eliminadas.

Los identificadores internos de petición que usa `DetailScreen` y resuelve `MainActivity` son:

- `vidly:<id>` → `WebSourceRegistry.vidlyUrl(...)` → botón **Reproducir**.
- `videoapi:<id>` → `WebSourceRegistry.videoApiUrl(...)` → botón **Latino**.
- `playerlab:<id>` → no pasa por `WebSourceRegistry`: `MainActivity` navega a la ruta `playerlab/{titleId}/{titleName}` → `PlayerLabScreen` → botón **PlayerLab** (solo `tmdb-movie-<id>`).
- `simuladores:<id>|s<temporada>|e<episodio>` → ruta especial de Los Simuladores mediante OK.ru.

Para episodios, el ID lleva el formato `tmdb-tv-<id>|s<temporada>|e<episodio>`. Para películas se usa `tmdb-movie-<id>`. Las URLs de Vidly siguen `/movie/{tmdbId}` y `/tv/{tmdbId}/{season}/{episode}`, con `autoplay=1`. VideoAPI construye su URL desde el ID de catálogo.

## Archivos principales

- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`: navegación principal, resolución de solicitudes de reproducción y flujo del siguiente episodio.
- `app/src/main/java/com/quilmes/xuper/data/WebSourceRegistry.kt`: detección de dominios y construcción de URLs de Vidly y VideoAPI; también marca anime para elegir la ruta de VideoAPI.
- `app/src/main/java/com/quilmes/xuper/data/PlayerLabRepository.kt`, `data/PlayerLabEngine.kt` y `ui/screens/PlayerLabScreen.kt`: PlayerLab (ver sección propia).
- `app/src/main/java/com/quilmes/xuper/ui/screens/DetailScreen.kt`: ficha, selección de temporada/episodio y botones de reproducción. Mantener el orden oficial: Reproducir, Latino, PlayerLab (este último solo en películas, no en episodios).
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`: reproductor web embebido, mandos/teclas, controles superpuestos, filtrado de solicitudes y tratamiento de errores.
- `app/src/main/java/com/quilmes/xuper/ui/screens/WebHubKeyRouter.kt`: enrutamiento de teclas del mando para controles web.
- `app/src/main/java/com/quilmes/xuper/ui/screens/XuperWebRequestFilter.kt` y `XuperBraveAdblock.kt`: filtrado de solicitudes y protección frente a publicidad/redirecciones.
- `app/src/main/java/com/quilmes/xuper/data/TmdbCatalogService.kt`: metadatos TMDB de catálogo, temporadas, episodios y títulos relacionados.
- `app/src/main/java/com/quilmes/xuper/data/M3uRepository.kt` e `IptvAccountTextParser.kt`: soporte de listas/fuentes IPTV y parsing de cuentas M3U.

La copia antigua `PlayerScreen.kt` que había en la raíz del proyecto se eliminó (tenía código de FrameXTV). **Editar siempre** `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`.

## Reproducción y mando a distancia

- La reproducción debe permanecer dentro de Xuper cuando sea posible; no abrir una aplicación externa ni dejar una página del proveedor navegable como experiencia final si se puede controlar el reproductor embebido.
- Vidly usa una navegación de mando específica y el agente JavaScript de control por frames cuando WebView lo permite. Se intentan habilitar controles HTML5 y enviar comandos de reproducción/pausa al vídeo accesible. No asumir que un proveedor expone subtítulos/audio si la propia página no ofrece esas pistas.
- VideoAPI/Latino utiliza el agente de frames y controles superpuestos propios cuando están disponibles.
- No interceptar indiscriminadamente OK/flechas: puede impedir navegar los controles originales del proveedor. Al modificar el mando, razonar por proveedor y verificar los eventos recibidos.
- Los errores de carga o reproducción deben ser visibles y útiles. Evitar estados de carga infinitos, cierres de la app y falsos mensajes de éxito.
- El siguiente episodio debe conservar el proveedor actual: VideoAPI continúa con VideoAPI y Vidly con Vidly, salvo que el usuario pida otro comportamiento.

## PlayerLab (reproductor de pruebas por torrent)

Port a Android del proyecto "Xuper Player Lab" (Node + WebTorrent). Flujo, igual que en el lab: **ID de TMDB → IMDb → catálogo YTS → archivo `.torrent` → streaming → reproducción**.

1. `PlayerLabRepository`: pide `external_ids` a TMDB (reusa `TmdbExternalIdResolver`, con caché) y consulta `list_movies.json?query_term=<imdb>` en la API compatible con YTS (`https://movies-api.accel.li/api/v2`, respaldo `https://yts.mx/api/v2`). **Exige coincidencia exacta de `imdb_code`**; nunca se elige por parecido de título. Devuelve las variantes (calidad, tipo, tamaño, códec, audio, seeds, peers, `url` del .torrent). Solo se usan variantes con URL `https://` del .torrent.
2. `PlayerLabScreen`: lista de calidades (botón ▶ Reproducir por cada una), pantalla de preparación (pre-buffer, velocidad, peers) y, cuando está listo, `PlayerScreen(directOnly = true)` con la URL local. Atrás vuelve a la lista de calidades; salir de la pantalla detiene todo y **borra lo descargado** (`externalCacheDir/playerlab`).
3. `PlayerLabEngine`: baja el `.torrent` (HTTPS, 20 B–5 MB), elige el video más grande (mp4/webm/m4v/mov/mkv), lo agrega a **libtorrent4j** (`org.libtorrent4j:*:2.1.0-31`, binarios de arm, arm64, x86, x86_64), descarga solo ese archivo en modo secuencial con `setPieceDeadline` (principio, final y ventana de 16 piezas por delante de la posición de lectura) y lo sirve por un HTTP mínimo en `127.0.0.1:<puerto>` con soporte de `Range` (equivale al `createServer` de WebTorrent). Abre el reproductor cuando hay ~10 MB de pre-buffer.
4. `NativePlayerScreen` usa timeouts de 30 s/120 s solo para URLs `http://127.0.0.1:` (un tramo sin piezas puede tardar más que los 8 s por defecto).

Clave de TMDB: `BuildConfig.TMDB_API_KEY`, que el workflow toma del secret de GitHub `TMDB_API_KEY` (ya estaba configurado para el resto de la app). Sin clave, PlayerLab muestra un mensaje que lo indica.

Limitaciones conocidas: solo películas (el lab no implementa series; en episodios no hay botón PlayerLab); la API YTS es de terceros y puede no tener un título; un torrent con pocos seeds puede tardar o no arrancar; ExoPlayer no reproduce todos los códecs/contenedores; subtítulos externos todavía no integrados (el lab tampoco; ExoPlayer solo muestra pistas embebidas en el archivo, botón "CC Subtítulos"). **No se probó en un dispositivo real**: se verificó la sintaxis y el tipado del motor contra stubs de libtorrent4j, pero la API real de libtorrent4j (`SessionManager.download/find`, `TorrentHandle.setPieceDeadline/havePiece/prioritizeFiles`, `TorrentFlags.SEQUENTIAL_DOWNLOAD`) y el streaming en el TV quedan por validar al compilar en GitHub Actions y probar.

## Catálogo, episodios y contenido especial

- TMDB es la fuente de metadatos; los IDs TMDB no deben confundirse con IDs IMDb.
- Las series muestran temporadas y episodios con nombre y sinopsis, y el usuario selecciona el episodio que desea reproducir.
- Los Simuladores usa una ruta especial y enlaces de vídeo de OK.ru, y debe conservar su comportamiento particular.
- Xuper también contiene secciones de anime, contenido adulto, biblioteca/favoritos, búsqueda y fuentes IPTV. No eliminar esas funciones como parte de un cambio de reproductores.
- Stremio ya no es parte de la ruta principal de reproducción deseada por el usuario. No reintroducir integración de Stremio como solución de reproducción sin una petición explícita.

## Entorno técnico y validación

- Android/Kotlin, Jetpack Compose (incluyendo componentes TV), WebView/AndroidX WebKit y Media3/ExoPlayer para las rutas que correspondan.
- El módulo de aplicación está en `app/`; el archivo Gradle de app es `app/build.gradle.kts`.
- En el historial del proyecto se usó `gradle assembleRelease --no-daemon` para validar builds. Verificar siempre la configuración actual antes de tocar versiones o DSL de Gradle.
- Tras cambios de fuentes, buscar referencias residuales a `vidzy`, `Prueba 2` y nombres viejos de botones; revisar tanto `DetailScreen.kt` como `MainActivity.kt` y `WebSourceRegistry.kt`.
- Para cambios de JS incrustado en Kotlin, extraer el script y ejecutar una comprobación de sintaxis JS cuando sea posible. Para un ZIP de entrega, comprobar su integridad con `unzip -t`.
- No declarar que el APK compila o que un mando funciona físicamente si no se verificó en el dispositivo. Diferenciar pruebas estáticas/simuladas de pruebas reales.

## Reglas de trabajo para futuras IAs

1. Leer este README y luego inspeccionar el código actual antes de actuar; este archivo orienta, pero el código manda.
2. Mantener los cambios acotados, explicar qué archivos se tocaron y por qué, y no revertir funciones no relacionadas.
3. No volver a crear documentación Markdown paralela. Actualizar este `README.md` único cuando cambien decisiones, arquitectura o comportamiento.
4. No afirmar que una función está arreglada por haber cambiado código: indicar con precisión qué se verificó y qué queda por probar en una TV/dispositivo.
5. El usuario prefiere cambios concretos y comprobables, y trabaja pasando ZIPs entre distintas IAs. Cuando corresponda, entregar un ZIP del proyecto limpio sin `.git` ni archivos temporales.
