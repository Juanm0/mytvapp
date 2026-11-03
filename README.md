# Xuper — documentación única para futuras IAs

> **Este archivo es la fuente de verdad del proyecto.** No reconstruir decisiones a partir de documentación antigua: los Markdown anteriores se consolidaron y eliminaron porque estaban desactualizados. Antes de cambiar código, inspeccionar el código actual y actualizar este README cuando cambie el comportamiento.

## Objetivo del proyecto

Xuper es una aplicación Android orientada a TV, desarrollada principalmente en Kotlin y Jetpack Compose. Usa TMDB para catálogo, fichas, temporadas, episodios y metadatos; integra reproducción web dentro de la app y también contempla fuentes IPTV/M3U. La experiencia debe funcionar con mando a distancia, mantener el vídeo dentro de Xuper cuando la fuente lo permita y mostrar errores reales en lugar de quedarse cargando indefinidamente.

## Orden y nombres oficiales de reproductores

En la ficha de películas y en cada episodio, las opciones oficiales son, en este orden:

1. **Reproducir** — Vidly (`vidly.pro`). Es el reproductor principal y reemplaza el antiguo botón llamado **Prueba**.
2. **Latino** — VideoAPI (`videoapi.la`; para rutas de anime también puede usarse `videoapp.zip`).
3. **Alternativo** — VidLux (`vidlux.xyz`), embed web por ID TMDB para películas y episodios.
4. **Prueba** — VideoAPI (`videoapi.la`) con la navegación de mando "de fábrica" de Xuper (la misma de Vidly/VidLux), sin hub ni agente de frames, cargado con la misma URL que Latino. Es un experimento: si anda bien en la TV, la idea es pasar Latino a este esquema. **No confundir con el antiguo botón "Prueba" (que era Vidly y hoy se llama "Reproducir")**. Ver sección "Prueba".

**PlayerLab (torrent con libtorrent4j/YTS) está eliminado** por pedido del usuario (se sospechaba que trababa toda la app): no quedan archivos, ruta `playerlab/...`, botón, dependencias `libtorrent4j` ni timeouts especiales para `127.0.0.1`. No volver a agregarlo salvo pedido explícito.

**FrameXTV (`framextv.cc` / `framextv.xyz`, botón "Alternativo") está eliminado** por pedido del usuario: no quedan rutas, hub, selector de proveedor/pistas ni funciones suyas. No volver a agregarlo salvo pedido explícito.

**VidFast (`vidfast.*`, botón "Alternativo 2") está eliminado** por pedido del usuario: no andaba bien en la TV (en PC sí). No quedan rutas, URL, botón ni scripts suyos. No volver a agregarlo salvo pedido explícito.

**Prueba 2 / Vidzy (`vidzy.org`) está eliminado** como opción de producto. No volver a agregarlo salvo pedido explícito del usuario. Las rutas, funciones y referencias a ese reproductor deben permanecer eliminadas.

Los identificadores internos de petición que usa `DetailScreen` y resuelve `MainActivity` son:

- `vidly:<id>` → `WebSourceRegistry.vidlyUrl(...)` → botón **Reproducir**.
- `videoapi:<id>` → `WebSourceRegistry.videoApiUrl(...)` → botón **Latino**.
- `vidlux:<id>` → `WebSourceRegistry.vidLuxUrl(...)` → `https://vidlux.xyz/embed/movie/{id}` o `/embed/tv/{id}/{season}/{episode}` → botón **Alternativo**.
- `prueba:<id>` → `WebSourceRegistry.videoApiPruebaUrl(...)` → misma URL de VideoAPI que Latino + fragmento `#xuper-prueba` (solo marca interna) → botón **Prueba** (ver sección "Prueba").
- `simuladores:<id>|s<temporada>|e<episodio>` → ruta especial de Los Simuladores mediante OK.ru.

Para episodios, el ID lleva el formato `tmdb-tv-<id>|s<temporada>|e<episodio>`. Para películas se usa `tmdb-movie-<id>`. Las URLs de Vidly siguen `/movie/{tmdbId}` y `/tv/{tmdbId}/{season}/{episode}`, con `autoplay=1`. VideoAPI construye su URL desde el ID de catálogo.

## Archivos principales

- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`: navegación principal, resolución de solicitudes de reproducción y flujo del siguiente episodio.
- `app/src/main/java/com/quilmes/xuper/data/WebSourceRegistry.kt`: detección de dominios y construcción de URLs de Vidly, VideoAPI (Latino y Prueba) y VidLux; también marca anime para elegir la ruta de VideoAPI.
- `app/src/main/java/com/quilmes/xuper/ui/screens/DetailScreen.kt`: ficha, selección de temporada/episodio y botones de reproducción. Mantener el orden oficial: Reproducir, Latino, Alternativo, Prueba (en películas y en episodios).
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`: reproductor web embebido, mandos/teclas, controles superpuestos, filtrado de solicitudes y tratamiento de errores.
- `app/src/main/java/com/quilmes/xuper/ui/screens/WebHubKeyRouter.kt`: enrutamiento de teclas del mando para controles web.
- `app/src/main/java/com/quilmes/xuper/ui/screens/XuperWebRequestFilter.kt` y `XuperBraveAdblock.kt`: filtrado de solicitudes y protección frente a publicidad/redirecciones.
- `app/src/main/java/com/quilmes/xuper/data/TmdbCatalogService.kt`: metadatos TMDB de catálogo, temporadas, episodios y títulos relacionados.
- `app/src/main/java/com/quilmes/xuper/data/M3uRepository.kt` e `IptvAccountTextParser.kt`: soporte de listas/fuentes IPTV y parsing de cuentas M3U.

La copia antigua `PlayerScreen.kt` que había en la raíz del proyecto se eliminó (tenía código de FrameXTV). **Editar siempre** `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`.

## Reproducción y mando a distancia

- La reproducción debe permanecer dentro de Xuper cuando sea posible; no abrir una aplicación externa ni dejar una página del proveedor navegable como experiencia final si se puede controlar el reproductor embebido.
- Vidly, VidLux y Prueba usan WebView embebido. Todos reciben la navegación de mando específica de Xuper cuando AndroidX WebKit permite inyectar JavaScript al inicio del documento; VidLux y Prueba conservan también los controles originales del proveedor. VidLux carga subtítulos automáticamente (incluye español según el título), sin parámetro de idioma conocido: no asumir que haya subtítulos en español para todos los títulos.
- VideoAPI/Latino utiliza el agente de frames y controles superpuestos propios cuando están disponibles.
- No interceptar indiscriminadamente OK/flechas: puede impedir navegar los controles originales del proveedor. Al modificar el mando, razonar por proveedor y verificar los eventos recibidos.
- Los errores de carga o reproducción deben ser visibles y útiles. Evitar estados de carga infinitos, cierres de la app y falsos mensajes de éxito.
- El siguiente episodio debe conservar el proveedor actual: VideoAPI continúa con VideoAPI y Vidly con Vidly, salvo que el usuario pida otro comportamiento.

## VidLux (reproductor alternativo)

- Se abre desde el botón **Alternativo** en películas y episodios (id interno `vidlux:<id>`; `DetailScreen` → `MainActivity` → `WebSourceRegistry.vidLuxUrl(...)`).
- URL: `https://vidlux.xyz/embed/movie/{tmdbId}?color=16A085`; series: `https://vidlux.xyz/embed/tv/{tmdbId}/{season}/{episode}?color=16A085` (season y episode son obligatorios; sin temporada se usa `1/1`). VidLux publica dominios alternativos (`vidlux.top`, `.site`, `.online`) con la misma ruta; el código los reconoce pero genera siempre `vidlux.xyz`.
- Se carga dentro del WebView de Xuper, no abre otra aplicación.
- **Parámetros**: solo se usa `color` (hex sin `#`; verde `16A085`), que es el único que figura en el ejemplo de integración de VidLux. VidLux menciona además "autoplay toggle" y "server pinning", pero **no se conoce el nombre de esos parámetros** (la página `vidlux.xyz/docs` no se pudo abrir en la sesión): no se inventaron. Si se consigue la doc oficial, revisar: autoplay, servidor fijo, idioma de subtítulos/audio y botón de pantalla completa.
- **Arranque automático con sonido** (`XUPER_VIDLUX_AUTOPLAY_JS` en `PlayerScreen.kt`): `WebEmbedPlayer` ya fija `settings.mediaPlaybackRequiresUserGesture = false`; además inyecta el script con `addDocumentStartJavaScript` en todos los frames (respaldo: `evaluateJavascript` en `onPageFinished` si el WebView no soporta scripts de inicio). El script espera a que el reproductor esté listo y lo inicia con la API estándar del `<video>` (`muted=false` + `play()`) y/o tocando el botón Play real de la página (sin UI propia). Si `play()` es rechazado **no** cae a modo muteado: reintenta. Una vez que reproduce, durante ~12 s fuerza `muted=false`/`volume>0` y, como último recurso, toca el botón real de "activar sonido". Se cancela si la persona aprieta una tecla del mando antes de que arranque. Deja el resultado en Logcat (`adb logcat -s XuperVidLux`): `estado final: ... OK con sonido` o `ADVERTENCIA: sigue muteado`. Sin botones propios de volumen/mute (el volumen es el del televisor). El script espera cada 800 ms y recorre pocos elementos para no cargar la TV.
- **Popups**: VidLux declara ~2 popups publicitarios cada 30 minutos. El WebView ya los bloquea (`setSupportMultipleWindows(false)`, `onCreateWindow = false`, `window.open` anulado por el ad guard).
- La navegación del mando usa el agente remoto ya utilizado por Vidly cuando AndroidX WebKit admite scripts al inicio del documento (`XUPER_REMOTE_NAV_JS`, `HubAwareWebView`); VidLux conserva sus controles originales. Los selectores del agente son genéricos: **verificar en la TV** que el mando llega al selector de servidores y a los controles de VidLux.
- Servidores internos de VidLux (según su presentación): Star, Rocket, Quilox, Spider, Nova, Magic, Aura, Dubai, Astra, con fallback automático; también calidad (144p–1080p) y pistas de audio/subtítulos seleccionables dentro del reproductor.
- Verificación hecha: lógica del script probada con jsdom (video que arranca muteado, póster con botón Play que crea el `<video>`, `play()` rechazado) y sintaxis con `node --check`. **No se compiló el APK ni se probó en una TV.**

## Prueba (VideoAPI con navegación de fábrica)

- Objetivo: que VideoAPI se pueda navegar con el mando por **todas las opciones de la pantalla** (como en Alternativo), en vez de pasar por el hub propio de Latino. La navegación de Prueba fue probada en la TV y **funciona bien**: no cambiarla.
- La URL base es la de `WebSourceRegistry.videoApiUrl(...)` más el fragmento `#xuper-prueba` (los fragmentos no se envían al servidor). `WebSourceRegistry.isVideoApiPruebaUrl(url)` lo detecta.
- En `PlayerScreen.kt` (`WebEmbedPlayer`) `isPrueba` fuerza `isVideoApi = false`, por lo que **no** se activan el hub, el agente de frames, la sonda de Latino ni la selección automática de español de Latino; en cambio recibe `XUPER_REMOTE_NAV_JS` y `consumeDpad`, igual que Vidly/VidLux.
- **Carga**: Prueba pasa por `videoApiEmbedUrl(...)` igual que Latino (sin el fragmento), es decir con `autoplay=1&subtitles=1&hide_controls=0&skin=netflix` y `start_at` si hay posición guardada. Antes cargaba la URL cruda (sin `skin=netflix` ni `hide_controls=0`) y en la TV **se trababa la transmisión y la pantalla no se despejaba** (los controles quedaban a la vista); la hipótesis es que el skin por defecto de VideoAPI es el responsable, ya que era la diferencia principal con Latino. **Hipótesis sin verificar en la TV**: si vuelve a fallar, los siguientes sospechosos son `thumbnails=1`, el script de navegación inyectado en todos los frames y el script de reanudación (`webPlaybackResumeScript`). La app no tiene ningún buffer propio en la ruta de WebView: la reproducción la maneja VideoAPI.
- Riesgo conocido: el script de navegación fue probado con el skin por defecto; con `skin=netflix` el DOM de los controles puede ser distinto. **Verificar en la TV que el mando siga llegando a todos los controles.**
- El siguiente episodio conserva Prueba (`MainActivity`).
- **Latino no se tocó**: sigue con su hub.

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
