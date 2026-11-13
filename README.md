# Xuper — documentación única para futuras IAs

> **Este archivo es la fuente de verdad del proyecto.** No reconstruir decisiones a partir de documentación antigua: los Markdown anteriores se consolidaron y eliminaron porque estaban desactualizados. Antes de cambiar código, inspeccionar el código actual y actualizar este README cuando cambie el comportamiento.

## Objetivo del proyecto

Xuper es una aplicación Android orientada a TV, desarrollada principalmente en Kotlin y Jetpack Compose. Usa TMDB para catálogo, fichas, temporadas, episodios y metadatos; integra reproducción web dentro de la app y también contempla fuentes IPTV/M3U. La experiencia debe funcionar con mando a distancia, mantener el vídeo dentro de Xuper cuando la fuente lo permita y mostrar errores reales en lugar de quedarse cargando indefinidamente.

## Orden y nombres oficiales de reproductores

En la ficha de películas y en cada episodio, las opciones oficiales son, en este orden:

1. **Reproducir** — Vidly (`vidly.pro`). Es el reproductor principal y reemplaza el antiguo botón llamado **Prueba**.
2. **Latino** — VideoAPI (`videoapi.la`; para rutas de anime también puede usarse `videoapp.zip`).
3. **Alternativo** — VidLux (`vidlux.xyz`), embed web por ID TMDB para películas y episodios. **Botón retirado de la ficha el 06/11/2026** (pedido del usuario; ver sección "INICIO ↔ LAB"). El código de `vidlux:` en `MainActivity`/`WebSourceRegistry` sigue ahí por si se pide volver a mostrarlo.
4. **Prueba** — VideoAPI (`videoapi.la`) con la navegación de mando "de fábrica" de Xuper (la misma de Vidly/VidLux), sin hub ni agente de frames, cargado con la misma URL que Latino. Es un experimento: si anda bien en la TV, la idea es pasar Latino a este esquema. **No confundir con el antiguo botón "Prueba" (que era Vidly y hoy se llama "Reproducir")**. Ver sección "Prueba".

**PlayerLab (torrent con libtorrent4j/YTS) está eliminado** por pedido del usuario (se sospechaba que trababa toda la app): no quedan archivos, ruta `playerlab/...`, botón, dependencias `libtorrent4j` ni timeouts especiales para `127.0.0.1`. No volver a agregarlo salvo pedido explícito.

**FrameXTV (`framextv.cc` / `framextv.xyz`, botón "Alternativo") está eliminado** por pedido del usuario: no quedan rutas, hub, selector de proveedor/pistas ni funciones suyas. No volver a agregarlo salvo pedido explícito.

**VidFast (`vidfast.*`, botón "Alternativo 2") está eliminado** por pedido del usuario: no andaba bien en la TV (en PC sí). No quedan rutas, URL, botón ni scripts suyos. No volver a agregarlo salvo pedido explícito.

**Prueba 2 / Vidzy (`vidzy.org`) está eliminado** como opción de producto. No volver a agregarlo salvo pedido explícito del usuario. Las rutas, funciones y referencias a ese reproductor deben permanecer eliminadas.

**CineSrc (`cinesrc.st`) no es un botón de la ficha**: el flujo principal de INICIO lo usa en segundo plano para capturar subtítulos y reproducir el video con Media3. No debe confundirse con los reproductores alternativos de la ficha.

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
- `app/src/main/java/com/quilmes/xuper/ui/screens/LabScreen.kt`: pantalla de catálogo principal usada por INICIO y BUSCAR (TMDB + reproducción CineSrc/VideoAPI). El nombre de archivo es histórico.
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
- **Despeje de pantalla (solo Prueba)**: tras la carga con `skin=netflix` la transmisión dejó de trabarse, pero la pantalla no se despejaba del todo. Causas probables (hipótesis, sin verificar qué elemento exacto quedaba a la vista): (1) el "mouse" falso que Xuper pone sobre el reproductor para despertar los controles (`HubAwareWebView.nudgeHover` + `wake()` del script) nunca se retiraba, y muchos reproductores no ocultan los controles mientras creen que el mouse está encima; (2) el recuadro amarillo del script solo se borraba si el elemento marcado dejaba de verse. Solución, activa **solo en Prueba** para no mover Vidly/VidLux: `HubAwareWebView(hoverExitDelayMs = 4000)` manda un `ACTION_HOVER_EXIT` 4 s después de la última tecla, y el script recibe `window.__xuperIdleHideMs=4000` (`remoteNavScript` en `WebEmbedPlayer`), con lo que a los 4 s sin teclas borra el recuadro y dispara `pointerout/mouseout/pointerleave/mouseleave` sobre el reproductor. Probado con jsdom (sin reposo: nada cambia; con reposo: recuadro oculto y eventos de salida enviados); **falta verificar en la TV**. Si todavía queda algo a la vista, hay que identificar qué elemento es (control del skin, título, recuadro) antes de seguir.
- El siguiente episodio conserva Prueba (`MainActivity`).
- **Latino no se tocó**: sigue con su hub.

## INICIO — reproductor único VideoAPI + CineSrc

- **Ruta activa del Inicio**: `MainActivity` navega a `home-player/{nombre}/{catalogId}` y abre `HomePlayerScreen.kt`. No depende de la categoría LAB ni de su reproductor experimental.
- **Video**: un WebView oculto abre `WebSourceRegistry.videoApiUrl(catalogId)` y captura el HLS `.m3u8` junto con `Referer`, `User-Agent` y cookies. La reproducción visible se realiza con el único reproductor nativo Media3.
- **Subtítulos**: otro WebView oculto abre `WebSourceRegistry.cineSrcUrl(catalogId)` y observa las pistas `.srt`/`.vtt` que CineSrc solicita, tanto como recursos directos como dentro de respuestas JSON. Las pistas capturadas se cargan en Media3 con CineSrc como `Referer`; no se muestra un segundo reproductor.
- **Compatibilidad Android TV**: la captura se instala al inicio del documento cuando WebView no soporta `DOCUMENT_START_SCRIPT`, además de interceptar solicitudes de red. Se reconocen URLs con extensión, `format=srt/vtt` y metadatos `m=`.
- **Nombres de pistas**: los alias `Spanish`, `Es Spanish`, `Español` y el código observado `Ea` se normalizan a `Español`; `Spanish (LA)` y variantes equivalentes se muestran como `Español (Latinoamérica)`. Se conserva el idioma normalizado para que Media3 también pueda identificarlo correctamente. Se priorizan las pistas en español y luego inglés.
- **Siguiente episodio**: se calcula desde TMDB y vuelve a pasar por el mismo resolvedor de VideoAPI + CineSrc.
- **LAB**: la categoría experimental queda separada; los cambios del reproductor principal no se implementan dentro de ella.
- **Verificación**: se revisó la integridad del código y del ZIP. Falta compilar con Gradle y probar la detección/selección de subtítulos en un Android TV real; el entorno actual no dispone de Gradle ni de un dispositivo TV conectado.

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


### LAB — selección de pistas y controles de reproducción

- El selector de subtítulos del LAB conserva todas las pistas capturadas identificadas como español (incluidas variantes regionales) y las pistas en inglés. La opción **Pistas de subtítulos** abre el selector nativo de Media3 para que se puedan probar las distintas versiones disponibles, en lugar de limitarse a una preferencia genérica de idioma.
- La opción **Resolución** abre el selector de pistas de video de Media3. Automático sigue siendo la selección predeterminada; las calidades manuales solo aparecen si la fuente realmente las ofrece.
- En el LAB, OK alterna pausa/reproducción cuando no hay paneles abiertos. Las flechas izquierda/derecha realizan saltos de 10 s, 30 s y hasta 120 s según la repetición de la tecla.
- Estos cambios se mantienen aislados del reproductor principal.


### Listas M3U propias (`assets/contenido_oculto/`)

- Cualquier `.m3u`/`.m3u8` que se ponga en `app/src/main/assets/contenido_oculto/` se registra solo como fuente (`buildRepository` en `MainActivity.kt`; se copia a `filesDir/contenido_oculto/` en `prepareLocalFiles`). El nombre del archivo es el nombre de la fuente. Se carga con `M3uRepository`, igual que las demás: `.mp4` => película, `S01E02`/`1x02` => serie, el resto => TV en vivo (clasificación por entrada, `classifyContent`). Solo entran URLs `http(s)`; las rutas locales de PC (`C:\...`) no se reproducen en la tele. **No verificado**: compilación ni prueba en TV.

### Sección Argentina (solo lista propia)

- La sección **Argentina** usa únicamente `app/src/main/assets/listas/argentina.m3u` (lista armada por el usuario en su PC, ~246 canales). Ya no se descarga `iptv-org/.../ar.m3u`, se quitó la fuente "Argentina · agregados" y no se mezclan canales Gran Hermano de otras listas. Orden: los canales conocidos primero (`argentinaRankNorm`), el resto alfabético; solo se quitan URLs repetidas. Para actualizarla, reemplazar ese archivo y compilar. `parse` también toma `user-agent`/`referrer` declarados en la línea `#EXTINF` (además de `#EXTVLCOPT`). **No verificado**: compilación ni prueba en TV.
- **Sin fallback en canales en vivo**: `getPlayableAlternatives` devuelve vacío para canales (`isLive`); ya no cambia a otra lista si un canal falla (Argentina tiene una única lista). Las películas/series siguen pudiendo tener alternativas entre listas. **Gran Hermano se eliminó** (fila de Inicio, categoría de la pestaña Argentina, prioridad de orden): ya no transmite. En la pestaña Argentina de `LibraryScreen` ya no se colapsan canales por marca: se muestran todas las URLs de la lista.
- **Canales en vivo: nunca cambian solos.** Si un canal falla, `PlayerScreen` muestra "No se puede reproducir este canal" con **Reintentar**, **Canal anterior** y **Canal siguiente** (además de CH+/CH− del mando); el usuario decide. Antes `onPlayerError` saltaba a otra entrada de igual nombre (`getPlayableAlternatives`), ahora vacío para canales en vivo.


### Compatibilidad con celular (táctil) y grilla de canales (06/11/2026)

- **Celular**: los componentes `androidx.tv.material3` (`Button`, `Surface`, `Card`) están hechos para mando y no disparaban `onClick` con el dedo. Se agregó `ui/components/TouchSupport.kt` con envoltorios del **mismo nombre y firma** (solo los parámetros que usa el proyecto: `Button(onClick, modifier, enabled)`, `Surface(onClick, modifier, shape, colors)`, `Card(onClick, modifier, shape, scale, border)`). Delegan en el componente de TV (el mando funciona igual) y suman detección de toque (`detectTapGestures`) con antirrebote de 250 ms para que no haya doble clic si el componente original ya procesa el toque. Los archivos que usaban los componentes de TV ahora importan estos envoltorios (`import com.quilmes.xuper.ui.components.Button/Surface`; en `PosterCard`, `SideBar` y `PinDialog` se quitó el import de TV porque están en el mismo paquete). **Para botones/tarjetas nuevos usar siempre estos envoltorios**, no los de `androidx.tv.material3`. La orientación sigue forzada a horizontal y el manifest ya declara `touchscreen`/`leanback` como no requeridos (el mismo APK sirve para TV y celular). **No verificado**: compilación ni prueba en celular/TV. Pendiente a propósito: los paneles del reproductor LAB (`labMode`) se manejan solo con D-pad; en celular se ve el video pero ese panel no tiene toque.
- **Pestaña Argentina** (`LibraryScreen`, modo ARGENTINA), de arriba hacia abajo: fila **Canales populares** (TN, La Nación+, Crónica, El Trece, Telefe, América, C5N, El Nueve, TV Pública, en el orden de `argentinaPriority`; si ninguno coincide se muestran los primeros 12), fila **Deportes Argentina** (igual que antes) y la grilla **Todos los canales (N)** con *todos* los canales de `argentina.m3u` (populares primero, después alfabético; solo se quitan URLs repetidas). Cada tarjeta (`ChannelGridCard`) es una sola pieza seleccionable: etiqueta "Argentina", logo (sobre placa clara para que se vea cualquier logo), nombre y barra "Ver canal". Las columnas se calculan según el ancho disponible (mínimo 2). Reproducir sigue usando `onPlay(title.id)`, así que CH+/CH−, "No se puede reproducir este canal" y el resto no cambian. La antigua fila larga "Canales argentinos" se reemplazó por "Canales populares" + la grilla.


### Canales en vivo: reconexión automática (05/11/2026)

- **Problema**: en la TV, canales como TNT Sports / ESPN Premium (que se traban en partidos de mucha demanda) quedaban congelados y no reconectaban; salir y volver a entrar sí andaba. Causas en `NativePlayerScreen` (`PlayerScreen.kt`): (1) solo se reaccionaba a `onPlayerError`, y una traba en `BUFFERING` o un `STATE_ENDED` no dispara error, así que no pasaba nada; (2) cuando sí había error, el mensaje y el botón **Reintentar** estaban dentro de los controles, que se ocultaban solos a los 6 s, dejando la pantalla congelada y sin pistas.
- **Solución** (solo canales en vivo, `isLive = channelQueue.size > 1`; películas/series/LAB no cambian): `requestLiveReconnect(...)` reintenta el mismo canal con esperas crecientes (1, 2, 3, 5, 5, 8, 8, 10 s; 8 intentos). Se dispara por: error del player (incluye `BehindLiveWindow`), `STATE_ENDED`, o un vigilante que detecta ~15 s seguidos en `BUFFERING` con `playWhenReady`. El contador vuelve a 0 tras 15 s reproduciendo bien. Mientras reconecta se ve el cartel **Reconectando… (intento N)**. Si se agotan los intentos aparece el error de siempre con **Reintentar** / **Canal anterior** / **Canal siguiente**, y ahora los controles **no se ocultan mientras haya error**. **Reintentar** reinicia el contador. Sigue sin cambiarse solo de canal.
- **Logcat**: `adb logcat -s XuperLive` muestra cada reconexión y su motivo.
- **No verificado**: compilación del APK ni prueba en la TV. Observación: `TNT Sports SD` (`http://181.78.79.131:8000/play/a0mo`) no tiene extensión, así que `setUrl` no le asigna MIME y Media3 lo trata como progresivo; si esa fuente falla siempre, probar forzando `APPLICATION_M3U8`.


### LAB — sincronía de subtítulos (06/11/2026)

- **Qué hace**: en LAB, flecha abajo → OK en **Configuración** → nueva fila **Sincronía de subtítulos** (entre *Estilo* y *Resolución*). Con la fila seleccionada, **◀ adelanta** y **▶ atrasa** los subtítulos de a 0,5 s (mantener la tecla repite; rango ±60 s); **OK** vuelve a 0. El valor se ve en pantalla (`+1,5 s`). Las filas ahora son: 0 audio, 1 subtítulo ON/OFF, 2 pistas, 3 tamaño, 4 estilo, **5 sincronía**, 6 subir subtítulos (ver sección siguiente), 7 resolución, 8 salida.
- **Cómo funciona** (`data/SubtitleShiftDataSource.kt`): Media3 1.3.1 no tiene retardo de subtítulos. Se envolvió el `DataSource.Factory` del `ExoPlayer` (`SubtitleShiftDataSourceFactory`): los pedidos de las pistas externas `.srt`/`.vtt` se descargan completos, se les suma el desfase a las líneas `inicio --> fin` (conserva `,` en SRT y `.` en VTT, no toca el texto ni los acentos) y se entregan corregidos. Con desfase 0 pasa directo. El video/audio no se modifican, y solo afecta a las pistas externas (las de LAB); los demás reproductores no cambian.
- **Aplicación**: como el reproductor lee el subtítulo al preparar la fuente, ~1 s después del último ◀/▶ (`LaunchedEffect(labSubtitleDelayMs)`) se vuelve a preparar el mismo `MediaItem` en la posición actual y con el mismo estado de play/pausa. Se nota un corte breve (rebuffer) y mientras tanto el cartel dice **Aplicando…**. Al cargar otra fuente (`setUrl`) la sincronía vuelve a 0. No se guarda entre reproducciones.
- **Notas**: no hay forma de saber el desfase "correcto" automáticamente, se ajusta a ojo. Si cambiás de pista de subtítulos con *Pistas de subtítulos*, el desfase se mantiene (es global a todas las pistas externas). **No verificado**: compilación ni prueba en la TV/celular; la lógica de corrimiento de tiempos sí se probó aparte con casos SRT/VTT. El modal sigue siendo solo de mando (sin toque), y con la fila nueva es ~50 dp más alto.


### LAB — tiempo de espera de la carga (06/11/2026)

- **Problema**: `LabResolving` daba por fallida la carga a los 30 s (`RESOLVE_TIMEOUT_MS`) y mostraba "No se pudo obtener el video…". En una tele lejos del WiFi la carga tardaba más y nunca llegaba a reproducir, aunque en otra tele sí.
- **Ahora**: a los 30 s (`SLOW_NOTICE_MS`) aparece, debajo del contador, el aviso "Está tardando más de lo normal…" con **REINTENTAR**, **USAR REPRODUCTOR ORIGINAL** y **VOLVER**, pero **la carga sigue** por detrás: si el video aparece, se reproduce solo. Recién a los **5 minutos** (`RESOLVE_TIMEOUT_MS = 300_000`) se muestra el error de siempre ("…en 5 minutos"). El contador pasa a "N min NN s" desde el minuto. El aviso solo sale si todavía no se capturó el enlace del video.
- **Detalle técnico**: `LabResolving` recibe `onRetry`/`onOriginal`; en `LabPlayerScreen` se lo envolvió en `key(attempt)` para que REINTENTAR desde esta pantalla cree un `WebView` nuevo (antes solo se reintentaba desde la pantalla de error). Los toques automáticos al centro siguen siendo 3 (a los ~3,5/7/10,5 s), sin cambios. **No verificado**: compilación ni prueba en la TV.


### LAB — subir subtítulos desde el celular con QR (06/11/2026)

- **Uso**: en LAB, flecha abajo → OK en **Configuración** → fila **Subir subtítulos** (OK) → aparece un **QR al lado del modal** (en pantallas angostas se superpone al centro). Se escanea con el celular (**mismo WiFi que la tele**) y abre una página servida por la propia tele con dos campos: **Nombre de los subtítulos** y **Archivo (.srt o .vtt)**, botón **Enviar a la tele**, luego **Subir otro**. La página también lista los subtítulos guardados para ese título con botón **Borrar**. BACK cierra primero el QR y después el modal; cerrar el modal apaga el servidor.
- **Cómo funciona**: `data/LabSubtitleUploadServer.kt` es un mini servidor HTTP con `ServerSocket` (sin librerías), puerto aleatorio, encendido solo mientras el QR está abierto. La dirección del QR (`http://IP:PUERTO/?t=CÓDIGO`) lleva un código de 8 caracteres; los pedidos sin ese código reciben 403. La IP se toma de `NetworkInterface` (WiFi/cable, sin permisos extra). Rutas: `GET /`, `GET /api/list`, `POST /api/upload?name=` (cuerpo = archivo crudo, máx. 3 MB), `POST /api/delete?id=`. El QR se genera con **ZXing core** (`ui/components/QrCode.kt`; dependencia nueva `com.google.zxing:core:3.5.3` en `app/build.gradle.kts`, hace falta internet en el próximo sync de Gradle).
- **Guardado** (`data/LabSubtitleStore.kt`): en `files/lab-subtitles/<id del título>/` con `index.json`. La clave es el `catalogId` de LAB, que en series incluye temporada y episodio, así que **cada episodio tiene sus propios subtítulos**. Al subir, el archivo se pasa a **UTF-8** (detecta UTF-8, UTF-16 y Windows-1252/Latin-1, para que no se rompan ñ y tildes) y se rechaza si no tiene líneas `-->`. Se guarda como `.srt` o `.vtt` según empiece con `WEBVTT`.
- **Reproducción**: al abrir un título de LAB se cargan sus subtítulos guardados (`uploadedSubtitleTracks`) y se suman **antes** de los capturados de CineSrc, con idioma `es`, etiqueta `Nombre · Subido` y `SELECTION_FLAG_DEFAULT`, para que ganen en la selección automática. Al recibir uno nuevo se vuelve a preparar el `MediaItem` en la misma posición (breve corte, igual que con la sincronía). Si ya se había elegido a mano otra pista en *Pistas de subtítulos*, esa elección puede mantenerse: hay que elegir la nueva ahí. Para leer archivos locales, el `DataSource` pasó a `DefaultDataSource.Factory(context, dataSourceFactory)` (http sigue igual). La **sincronía** también corrige estos archivos (es la misma envoltura).
- **Límites**: se asume que lo subido es español (`es`). Si la tele y el celular están en redes distintas, o el router aísla los dispositivos ("red de invitados"), el celular no llega a la tele. Sin pantalla táctil en el modal (solo mando). **No verificado**: compilación ni prueba en TV/celular; solo se comprobó la sintaxis del Kotlin y del JavaScript de la página.


### INICIO ↔ LAB, Latino en LAB y barra lateral en celular (06/11/2026)

- **Contexto**: CineSrc (`cinesrc.st`) se cayó un rato mientras se veía una película; el usuario quiere dejar LAB como pantalla principal y tener Latino como respaldo. Pendientes del usuario: buscar otro servidor tipo CineSrc (un espejo) y buscar cómo conseguir audio latino en LAB. Cuando haya un dominio nuevo, se agrega en `WebSourceRegistry.cineSrcUrl` / `isCineSrcHost`.
- **Intercambio** (`MainScreen.kt`): el ítem **INICIO** (⌂, `dest = "home"`, el que abre la app) ahora muestra `LabScreen` (catálogo TMDB + reproducción con CineSrc) y el ítem **LAB** (🧪, `dest = "lab"`) muestra el `HomeScreen` de antes (héroe, filas TMDB, Argentina, VOD de listas). Los nombres, íconos y posiciones de la barra no cambiaron; solo el contenido. Atrás sigue volviendo a INICIO.
- **Se eliminó del inicio de antes (ahora LAB)**: en `DetailScreen.kt` los botones **Alternativo** (VidLux) y **LAB** (CineSrc), en películas y en cada episodio (quedan Reproducir, Latino y Prueba); y en `HomeScreen.kt` la fila **"LAB · TMDB + CineSrc"**. Esta ficha (`DetailScreen`) es la que usan todas las pantallas que no son LAB.
- **Latino como alternativa en LAB** (`LabScreen.kt`, parámetro nuevo `onPlayLatino`): en películas, botón **▶ LATINO** al lado de **▶ REPRODUCIR**; en series, botón **Reproductor: CINESRC / LATINO** que alterna qué reproductor usan los episodios (por defecto CineSrc). `MainActivity` (`onPlayLabLatino`) arma `WebSourceRegistry.videoApiUrl(catalogId)` y navega a `external-player/...` igual que el botón Latino de la ficha normal (mismo reproductor con hub, siguiente episodio con VideoAPI, entra en "Continuar viendo"). Ojo: LATINO abre el reproductor web de VideoAPI, **no** el reproductor nativo de LAB (sin sincronía/subida de subtítulos ni panel LAB).
- **Barra lateral en celular** (`SideBar.kt`): la lista de ítems ahora se desplaza con el dedo (con 9 ítems no entraba en una pantalla baja de celular horizontal y los últimos, entre ellos el que está debajo de ANIME, quedaban fuera de la vista), en pantallas de menos de 500 dp de alto los ítems son más compactos, y **tocar el logo** muestra/oculta los nombres (con el dedo no hay foco que expanda la barra). Elegir un ítem la vuelve a cerrar. El mando funciona igual.
- **No verificado**: compilación del APK ni prueba en TV/celular. La causa de la barra en celular es una deducción por el alto de pantalla, no se pudo ver el celular.


### INICIO estilo Netflix (06/11/2026)

- **Pedido del usuario**: que el inicio (ahora la pantalla LAB, ver "INICIO ↔ LAB") se vea más lindo, tipo Netflix, con estrenos y cosas así.
- **Qué es** (`ui/screens/LabHome.kt`, usado por `LabScreen.kt`): `LabScreen` tiene una pestaña especial `LAB_HOME_TAB = "home"` que es la de arranque (`LabSession.tabId`). En ese modo se ve una barra de pestañas (● INICIO, TENDENCIAS, PELÍCULAS, SERIES, TOP PELÍCULAS, TOP SERIES, EN CARTELERA, AL AIRE, 🔍 BUSCAR) y debajo `LabHome`: un **banner grande** (fondo de TMDB, "🔥 EN TENDENCIA", título, año/★, sinopsis y **▶ VER AHORA**, que abre la ficha LAB) y filas horizontales: **Top 10 de la semana** (con números grandes), **Estrenos en cartelera** (`movie/now_playing?region=AR`), **Próximamente** (`movie/upcoming?region=AR`), **Series que están al aire**, populares y mejor valoradas (películas y series). Todo sale de TMDB con `loadLabPage` (sin adultos, es-AR). Los títulos abren la misma ficha de siempre (CineSrc o Latino).
- **Cómo funciona**: las 8 filas se piden en paralelo; si alguna falla se omite, y si fallan todas aparece el error con **REINTENTAR**. Quedan en memoria (`LabHomeCache`) para no recargar al volver de una ficha/reproductor. El banner es el primer título del Top 10 que tenga imagen y sinopsis (no rota solo). Alto del banner: 60 % del alto de pantalla, entre 200 y 340 dp.
- **Navegación**: tocar cualquier pestaña distinta de INICIO o **🔍 BUSCAR** abre el listado en grilla de antes (con buscador y CARGAR MÁS), que ahora tiene un botón **INICIO** para volver. Atrás desde un listado o una búsqueda vuelve al inicio; `LabPosterCard` pasó a `internal` para reusarla.
- **No verificado**: compilación del APK ni prueba en TV/celular (diseño y foco del mando sin ver en pantalla; el foco arranca en **VER AHORA**).


### Pestaña ARGENTINA: principales/secundarios, zapping de al lado y "Otros canales para ver" (06/11/2026)

> Esta sección **reemplaza** la descripción anterior de la pestaña Argentina (la pantalla ahora es `ui/screens/ArgentinaScreen.kt`; `LibraryScreen` quedó solo para MI LISTA y CONTINUAR VIENDO).

- **Orden de arriba hacia abajo**: título → (banner "Elegí el canal principal…" solo en modo secundario) → **Canales populares** → **Deportes Argentina** → grilla **Todos los canales (N)** (`argentina.m3u`) → **Otros canales para ver** (una grilla por group-title). Las tarjetas son las de siempre (`PosterCard` en filas, `ChannelGridCard` en grillas).
- **Bug corregido**: la fuente "Argentina" tenía `maxPerRow = 100` y recortaba la lista a 100 canales aunque `argentina.m3u` trae 246; ahora `maxPerRow = 5000`.
- **Añadir canales**: mantener apretado un canal de "Todos los canales" o de "Otros canales para ver" abre el menú con **Añadir principal** (se agrega al grupo Populares o Deportes según el nombre) y **Añadir secundario**. En este último caso se muestra un banner y se elige el principal al que pertenece; BACK o **Cancelar** salen del modo. Los canales agregados quedan guardados por URL.
- **Ordenar principales con el mando** (`ArgentinaScreen.kt`): mantener apretado OK sobre un principal activa el modo **ORDENANDO**. La tecla izquierda/derecha mueve ese canal en la fila y desplaza los demás; un nuevo OK confirma/lo suelta y BACK cancela sin guardar. El OK largo inicial no debe confirmar el movimiento al soltarlo.
- **Secundarios al pasar el foco**: al posar el foco sobre un principal se muestra automáticamente su panel debajo. Los secundarios aparecen como renglones compactos; al enfocar uno, a la derecha se muestra su tarjeta grande con logo. OK reproduce el secundario. Ya no hace falta abrir "Ver secundarios".
- **Subir al inicio**: después de agregar un principal, iniciar la selección de un secundario o terminar de mover un principal, la lista salta directamente al inicio (`scrollToItem`, sin animación) para evitar el desplazamiento entrecortado, y enfoca el canal correspondiente cuando el mando está activo.
- **Guardado**: `data/ChannelLayoutStore.kt` (SharedPreferences `xuper-channel-layout`, JSON por grupo `popular`/`sports`). Los canales se guardan por **URL del stream** (no por id). Mientras el usuario no personalice un grupo, se usan los principales por defecto (`argentinaPriority`).
- **Zapping (canal anterior/siguiente)**: ya no salta al siguiente alfabético de toda la lista. `data/ChannelQueueStore.kt` guarda la lista que se tenía a la vista al tocar el canal: la fila de principales, los secundarios (principal + secundarios), la grilla o el grupo de "Otros". `MainActivity` la usa en `channelQueue` si contiene el canal (y tiene ≥2); si no, la cola de siempre (`getChannelQueue`). Las demás pantallas (`MainScreen.plainPlay`) limpian la cola. Los secundarios no entran en el zapping de los principales.
- **Otros canales para ver**: listas `.m3u` en `app/src/main/assets/listas/` (hoy `tv-argentinas.m3u`, `tv-online.m3u`) se registran solas (`MainActivity.buildRepository`, `M3uSource.liveOthers = true`, `homeRows = 0`, copiadas a `filesDir/listas/` en `prepareLocalFiles`). Solo canales en vivo, sin URLs repetidas, sin contenido +18 oculto y **sin Gran Hermano**; se juntan por group-title entre listas ("Argentina" primero). Se pueden promover a principal/secundario como cualquier canal.
- **Cuentas en `config/`**: el usuario convirtió el TXT de cuentas a `.m3u` y va a borrar `config/iptv-accounts-raw.txt`. `generateActiveIptvAccounts` (Gradle) ahora usa `inputs.files(...)`, así el build no falla si el TXT no existe (genera un TXT vacío y `buildRepository` no registra cuentas). `assets/iptv-accounts-raw.m3u` es idéntica a `listas/tv-argentinas.m3u` y no está registrada (sobra).
- **Verificado en la revisión del 06/11/2026**: lectura del código y revisión estática de los cambios de interacción. **No verificado**: compilación del APK ni comportamiento físico del mando/scroll en TV. En esta versión el OK largo sobre un principal inicia el modo de ordenamiento; el segundo OK confirma. Si el control remoto no propaga las flechas al contenedor enfocado, revisar `onPreviewKeyEvent` de `ChannelGroupSection` y el `Card` de `TouchSupport.kt`.


### Arreglo de compilación en `ArgentinaScreen.kt` (06/11/2026)

- **Error** (`compileReleaseKotlin`): `Unresolved reference: nativeKeyEvent` (línea del import), `Unresolved reference: type` y `key` en `handleReorderKey`.
- **Causa**: `nativeKeyEvent` es una propiedad *miembro* de `KeyEvent` y no se importa (el `import androidx.compose.ui.input.key.nativeKeyEvent` no existe); en cambio `KeyEvent.key` y `KeyEvent.type` son propiedades de extensión y **sí** necesitan `import androidx.compose.ui.input.key.key` / `import androidx.compose.ui.input.key.type` (igual que ya hacen `PlayerScreen.kt` y `PinDialog.kt`).
- **Cambio**: se quitó el import de `nativeKeyEvent` y se agregaron los de `key` y `type`. `event.nativeKeyEvent.repeatCount` sigue funcionando sin import.
- **Verificado**: sintaxis Kotlin de todos los `.kt` (tree-sitter), que todos los imports de `com.quilmes.xuper.*` apuntan a algo que existe y que no hay declaraciones duplicadas en el mismo paquete. **No verificado**: la compilación completa (no hay Gradle/SDK acá); si Gradle muestra otro error, pegarlo tal cual.


### ARGENTINA / reproductor en vivo: mantener apretado, menú del principal y hub con secundarios (06/11/2026)

> **Reemplaza** lo dicho antes sobre "mantener apretado OK sobre un principal activa el modo ORDENANDO": ahora abre un **menú** y el movimiento se elige ahí.

- **Mantener apretado con el mando (bug)**: el `onLongClick` de `androidx.tv.material3` necesita que el control mande *repeticiones* de la tecla OK, y muchos controles no las mandan, así que "no dejaba mantener apretado". `Card` de `ui/components/TouchSupport.kt` ahora, cuando recibe `onLongClick`, **mide con un temporizador** (`LONG_PRESS_MS = 600`): `onPreviewKeyEvent` consume OK/Enter/Numpad Enter; si sigue apretado a los 600 ms dispara el "largo" y, si se suelta antes, ejecuta el clic normal al soltar. No depende de repeticiones. Con el dedo sigue funcionando por `detectTapGestures(onLongPress)`. Sin `onLongClick` el `Card` es igual que antes. El OK que inicia el modo ORDENANDO sigue ignorando repeticiones (`repeatCount == 0`).
- **Menú de un principal** (`ArgentinaScreen.kt`, `MenuRole.PRINCIPAL`): mantener apretado un canal principal abre el menú **Mover** (activa ORDENANDO: ◀ ▶ mueven, OK suelta, ATRÁS cancela) · **Ver secundarios (N)** (solo si tiene; fija el panel de secundarios debajo, útil con el dedo) · **Pasar a secundario** · **Quitar de principales** · **Restablecer este grupo**. Ya no se mueve solo al mantener apretado.
- **Por qué "no se veían los secundarios"**: sin el mantener apretado funcionando no se podía abrir "Añadir secundario" desde la grilla, así que no había ninguno. El panel de secundarios (aparece al enfocar el principal que los tiene) no se modificó.
- **Hub del reproductor en vivo** (`PlayerScreen.kt`, `NativePlayerScreen`): en canales en vivo (`channelQueue.size > 1`) el botón **"⟳ Cambiar reproductor"** (que en vivo ya no tenía alternativas) pasa a **"📺 Elegir canal secundario (N)"** (o "Sin canales secundarios" y un aviso de cómo agregarlos). Abre un selector con el principal (marcado ★) y los secundarios del grupo; al elegir uno, `ChannelQueueStore.set(relatedChannels)` deja el zapping CH+/CH− dentro de ese grupo y se llama a `onChangeChannel`. También aparece un botón **"📺 Canal secundario (N)"** junto a *Reintentar* cuando el canal falla, y los mensajes de error lo sugieren. Para las películas y series el botón sigue siendo "Cambiar reproductor".
- **Mejor acceso al hub (en vivo)**: ◀ ▶ ya no saltan 10 s (no sirve en vivo): con el hub oculto lo abren y con el hub abierto pasan de un botón al otro; el texto de ayuda se adapta. **El hub ya no se oculta mientras se usa**: cada tecla reinicia la cuenta (`hubTick`; 9 s en vivo, 6 s en lo demás) y no se oculta con el selector abierto. BACK cierra el selector antes de salir.
- **Datos**: `data/ChannelRelations.kt` → `groupFor(context, sections, streamUrl)` busca en `ChannelLayoutStore` (grupos `popular`/`sports`) el principal con sus secundarios al que pertenece el canal (sea principal o secundario) y devuelve `[principal] + secundarios` resueltos por URL. `MainActivity` (ruta `player/{titleId}`) lo calcula y lo pasa como `relatedChannels`. Solo lee lo que se armó en ARGENTINA.
- **Límite conocido**: al elegir un secundario desde un principal, el zapping CH+/CH− pasa a ser el del grupo (principal + secundarios) y no el de la fila de principales; para volver a esa fila, salir a ARGENTINA y elegir el canal.
- **Verificado**: sintaxis Kotlin (tree-sitter), imports del proyecto y ausencia de declaraciones duplicadas. **No verificado**: compilación ni prueba en la TV (en especial el temporizador de 600 ms con el control real y el foco al volver del selector).


### Menú de mantener apretado: nace bloqueado, se entra con ▶ (06/11/2026)

- **Problema**: al mantener apretado OK sobre un canal, el menú aparecía con la primera opción ya enfocada y el OK que todavía estaba apretado (o su repetición/soltada) la confirmaba al instante ("Mover", "Añadir principal"…).
- **Ahora** (`ChannelMenuDialog` en `ArgentinaScreen.kt`): el menú se abre **bloqueado**. Las opciones se ven pero no reciben foco (`focusProperties { canFocus = entered }`) y el contenedor (que tiene el foco) **se traga OK, ▲ y ▼**, así que soltar el OK no hace nada. **▶ (derecha)** entra al menú (una sola vez, ignora repeticiones) y el foco pasa a la primera opción; ahí **▲/▼** recorren las opciones y **OK** confirma. **◀ (izquierda)** cierra el menú en cualquier momento (también estando adentro) y **ATRÁS** lo cierra por el `onDismissRequest` del `Dialog`: para deshacer un mantener apretado sin querer sin romper nada. Un texto al pie indica qué tecla hacer según el estado.
- **Celular**: tocar una opción funciona directo (el bloqueo solo afecta al foco del mando).
- **No verificado**: compilación ni prueba en la TV (en especial que el foco inicial caiga en el contenedor del `Dialog`; el código reintenta hasta 6 veces cada 60 ms).


## Actualización: logos manuales y sección +18 por M3U

- En ARGENTINA, mantené apretado un canal y elegí **Robar icono**. Después seleccioná el canal destino: se le aplica el logo del primero y queda guardado entre reinicios.
- El menú contextual ahora se abre con **flecha abajo**; la flecha derecha ya no es la tecla de entrada al menú.
- ~~La sección +18 mostraba `contenido_oculto/xxx.m3u`~~ **Reemplazado el 13/11/2026**: ya no hay lista ni `xxx.m3u`; ver "Sección +18: catálogo dinámico + reproductor embebido".


### Reproductor principal: retroceso HLS y captura completa de subtítulos CineSrc (09/11/2026) — la captura de CineSrc fue RETIRADA el 13/11/2026, ver "Subtítulos de CineSrc (retirados)"

- **Retroceso** (`ui/screens/PlayerScreen.kt`): el seek hacia atrás usa `SeekParameters.PREVIOUS_SYNC` para caer en el keyframe anterior; el avance conserva `CLOSEST_SYNC`, que ya estaba funcionando. Se mantiene el agrupamiento de pulsaciones para ejecutar un único seek al terminar la ráfaga del mando.
- **Subtítulos** (`ui/screens/HomePlayerScreen.kt`): VideoAPI sigue aportando exclusivamente el HLS y CineSrc se abre en un WebView independiente por ID TMDB. La captura ahora observa pedidos directos y respuestas JSON/texto mediante `fetch`, `XMLHttpRequest` y recursos del frame, siguiendo el método del resolvedor original de CineSrc.
- **No se bloquean las descargas de subtítulos**: se registra la URL y se deja que CineSrc complete el request. Solo se bloquea el HLS del WebView auxiliar para que no compita con el reproductor nativo.
- **Ventana de captura**: espera a que aparezcan pistas y 1,2 s sin nuevas pistas; límite de 25 s, como el resolvedor CineSrc. Se registran cantidades y nombres en Logcat con la etiqueta `XuperSubtitles`.
- **Pistas**: se pasan todas las pistas capturadas a Media3, sin filtrarlas por idioma ni depender de la pista de audio seleccionada. Se conserva `display` completo; alias como `Ea`, `Es Spanish` y `Spanish` se normalizan a `Español`, mientras `Spanish (LA)` se muestra como `Español (Latinoamérica)`.
- **Pruebas estáticas**: el JavaScript de captura pasó `node --check` y una prueba local confirmó la extracción de dos URLs de subtítulos desde una respuesta JSON. No se pudo compilar el APK ni probar el comportamiento en una TV en este entorno.

## OpenSubtitles.com — ELIMINADO (13/11/2026)

- Se borró todo: `data/OpenSubtitlesService.kt`, el botón "Buscar subtítulos online" y el diálogo "Subtítulos online" de `PlayerScreen`, las pistas guardadas (`xuper-open-subtitles`) y la **API Key** (`openSubtitlesApiKey` / `BuildConfig.OPEN_SUBTITLES_API_KEY` en `app/build.gradle.kts`). **No volver a agregarlos** salvo pedido explícito. Como la clave estuvo publicada en el repositorio y dentro de los APK anteriores, conviene **revocarla en opensubtitles.com** (Consumers).
- Motivo: el usuario resolvió los subtítulos por otro camino (VideoAPI, ver abajo) y algunas pistas de OpenSubtitles/CineSrc se veían mal.

## Subtítulos de CineSrc (retirados — anotación por si hay que volver a usarlos)

Hasta el 13/11/2026 el Inicio (`HomePlayerScreen`) abría un **segundo WebView oculto** con el embed de CineSrc (`WebSourceRegistry.cineSrcUrl(catalogId)`, `https://cinesrc.st/embed/...`) solo para espiar qué subtítulos pedía su página. **Ya no se hace**, y se borró `LabPlayerScreen.kt` (versión vieja con el mismo método y sin uso). Cómo funcionaba, para reconstruirlo si hiciera falta:

1. WebView oculto cargando el embed de CineSrc, con `mediaPlaybackRequiresUserGesture = false`, JS y DOM storage; se le hacían hasta 3 toques reales en el centro (~3,5 s, 7 s, 10,5 s) para despertar el reproductor.
2. Captura de URLs de subtítulos por tres caminos: (a) `shouldInterceptRequest` con la regex `\.(srt|vtt)([?#]|$)|[?&]format=(srt|vtt)|[?&]m=[A-Za-z0-9_%=-]+` (sin bloquear el pedido); (b) un JS inyectado al inicio del documento (`addDocumentStartJavaScript`, o `evaluateJavascript` si el WebView no lo soporta) que envolvía `fetch` y `XMLHttpRequest`, escaneaba respuestas JSON/texto chicas (<500 KB) buscando URLs `https://...` con esa regex y recorría `performance.getEntriesByType('resource')` cada 700 ms; (c) todo se pasaba a Kotlin por un `@JavascriptInterface` (`XuperSubtitleBridge.add(url, referer)`).
3. Las URLs con `?m=<base64url>` traían metadatos JSON (`lang`, `display`, `src`, `hi`, `forced`): de ahí salían idioma y nombre ("Ea", "Es Spanish" -> "Español"; "Spanish (LA)" -> "Español (Latinoamérica)").
4. Ventana de captura: esperar a que aparezca la primera pista y 1,2 s sin pistas nuevas, máximo 25 s; el HLS del WebView auxiliar se bloqueaba para no competir con Media3. Logcat: `XuperSubtitles`.
5. Todas las pistas capturadas se pasaban a Media3 sin filtrar por idioma.

**Hoy**: los subtítulos del Inicio salen solo de `derivedVideoApiSubtitles` (ver sección "Subtítulos: fila Idioma, VideoAPI sin clics y saltos") más los que el usuario sube con el QR (`LabSubtitleStore`). El Inicio ya no espera a CineSrc: termina cuando VideoAPI confirma sus pistas (máx. 10 s tras capturar el video).

## Estructura de assets y listas (10/11/2026)

> **Reemplaza** las rutas de las secciones anteriores (`assets/mis-listas/`, `assets/otros-canales/` y `argentina.m3u` suelta en la raíz de assets): el usuario reorganizó las carpetas y el código se reapuntó (`MainActivity.kt`).

- `app/src/main/assets/listas/`: todas las listas. Las de **nombre fijo** (`argentina.m3u`, `lista-extendida.m3u`, `mi-lista.m3u`, y `iptv-extra.m3u`, `peliculas-dominio-publico.m3u`, `vod-original.m3u` si existieran) se copian a `filesDir/` y las lee `buildRepository` por nombre. Todo otro `.m3u` suelto en esta carpeta se registra solo como **"Otros canales para ver"** (hoy `tv-argentinas.m3u` y `tv-online.m3u`). Si una lista de nombre fijo está suelta en la raíz de assets, también se encuentra (respaldo).
- **Ignorados a propósito** (`IGNORED_LIST_FILES` en `MainActivity.kt`): `tv argentinas.m3u` y `tv-online2.m3u` (copias exactas de `tv-argentinas.m3u` y `tv-online.m3u`). `iptv-accounts-raw.m3u` e `index.m3u` sí se registran como fuentes explorables.
- `app/src/main/assets/contenido_oculto/`: cada `.m3u` se registra sola como fuente. La carpeta quedó vacía a propósito: el `xxx.m3u` de la sección +18 se eliminó (ver "Sección +18: catálogo dinámico + reproductor embebido"). Cualquier otro `.m3u` que se suelte ahí sigue registrándose solo.
- **Causa del problema que hubo**: el commit "añadimos las listas" movió los archivos a estas carpetas pero el código seguía leyendo las carpetas viejas, y por eso desaparecieron Argentina, "Otros canales" y +18.

## LAB oculto: opciones de desarrollador (10/11/2026)

- **Qué es "LAB" en el código**: el ítem 🧪 `dest = "lab"` de la barra lateral, que muestra `HomeScreen` (filas TMDB, Argentina y VOD de las listas). INICIO (`dest = "home"`) es `LabScreen` y **no** es parte de esto.
- **Estado** (`data/DeveloperMode.kt`): único lugar que decide si LAB está disponible. Solo el desbloqueo final se guarda (SharedPreferences `xuper-settings`, clave `developer_mode_unlocked`); el progreso intermedio vive en memoria del proceso. `DeveloperMode.disable()` lo apaga y reinicia la secuencia (no hay botón visible).
- **Secuencia**: 3 toques seguidos en INICIO (sin otro ítem en medio y con menos de 2 s entre toques) -> suena `sonido.mp3` y el gatito de la barra (`SideBar`, `logo_splash`) queda habilitado -> 67 toques al gatito. Al llegar a 64 sale el cartel "Usted es desarrollador en 3.2.1"; al 67 sale "Opciones de desarrollador habilitadas", se guarda y LAB aparece en la barra.
- **Gatito**: antes de habilitarse es una imagen inerte (sin foco ni toque). Habilitado usa `clickable` propio (no los envoltorios de `TouchSupport`, cuyo antirrebote de 250 ms perdería toques rápidos) y descarta las repeticiones de OK mantenido, así una pulsación larga cuenta una vez. Ya desbloqueado, tocar el logo vuelve a mostrar/ocultar los nombres en celular (función que antes estaba siempre activa).
- **Sonido** (`data/DeveloperUnlockSound.kt`): lee `app/src/main/assets/sonido.mp3` en tiempo de ejecución. Si falta, no falla: deja un aviso en Logcat (`adb logcat -s XuperDev`).
- **Buscador**: el acceso BUSCAR abre el buscador del catálogo principal (`LabScreen`), con TMDB `search/multi` y reproducción CineSRC/VideoAPI. Las listas LAB no se mezclan en sus resultados. El antiguo `SearchScreen` queda como implementación legada.
- **Verificado**: sintaxis Kotlin (tree-sitter) de los archivos tocados; la lógica de `DeveloperMode.kt` se compiló con kotlinc 1.9.24 contra stubs de Android y se ejecutó (contadores, ventana de 2 s, aviso en 64, desbloqueo en 67, persistencia entre procesos y `disable()`). **No verificado**: compilación del APK con Gradle ni comportamiento en una TV/celular (foco del gatito con el mando, sonido real, cartelitos).



## Cambios de mantenimiento — 10/11/2026

- **Buscador lateral**: BUSCAR abre el mismo catálogo/pantalla de INICIO (`LabScreen`), que consulta TMDB con `search/multi` y utiliza el flujo de reproducción CineSRC/VideoAPI. Ya no deriva a resultados de listas LAB.
- ~~OpenSubtitles unificado~~: **eliminado el 13/11/2026** (ver "OpenSubtitles.com — ELIMINADO").
- **Navegador de listas**: desde ARGENTINA se abre **Explorar todas las listas**. La pantalla muestra el contenido de las fuentes M3U cargadas, un índice a la derecha con nombre de fuente/categoría/cantidad y búsqueda que filtra listas, categorías y títulos. No crea una categoría nueva en la barra lateral.
- **Listas de assets/listas**: `index.m3u` deja de estar ignorada y se incorpora al navegador. Se mantienen fuera únicamente las dos copias exactas (`tv-online2.m3u`, `tv argentinas.m3u`); `iptv-accounts-raw.m3u` también se incluye porque contiene entradas distintas. Las fuentes `liveOthers` siguen agrupadas en ARGENTINA para su presentación habitual, pero su VOD también queda accesible desde el navegador.
- **Seek VOD**: se deja de forzar `CLOSEST_SYNC` en cada salto y se usa `SeekParameters.DEFAULT`, manteniendo el seek agrupado para evitar decenas de operaciones concurrentes en el mando. El watchdog de recuperación existente permanece activo.

## Actualizaciones automáticas (13/11/2026)

- **Para qué**: instalar Xuper una vez en una tele (ej. la de la abuela, beta tester) y actualizarlo después sin tocarla: solo necesita internet.
- **Cómo funciona** (`data/AppUpdater.kt`, `data/UpdateInstallReceiver.kt`, `ui/components/UpdateDialog.kt`; enganchado en `MainActivity`: `onResume` llama a `AppUpdater.check`, y `UpdatePrompt()` va debajo del `NavHost`):
  1. Al abrir la app (y al volver a ella, máx. una consulta cada 30 min) pregunta a `https://api.github.com/repos/<UPDATE_REPO>/releases/latest`.
  2. El **tag del release es `v<N>`** y `N` es el `versionCode` del APK. Si `N` > versionCode instalado, sale el cartel **"Actualización disponible"** (**Actualizar ahora** / **Más tarde**).
  3. Descarga el `.apk` del release a `cacheDir/updates/` (con barra de progreso), verifica tamaño, que sea de `com.quilmes.xuper` y más nuevo, y lo entrega a `PackageInstaller`. Android muestra su confirmación de instalación y hay que apretar **Instalar** con el mando. Al terminar, Android cierra la app: **hay que volver a abrirla**.
  4. **Primera vez**: Android pide permitir a Xuper "Instalar apps desconocidas". El cartel lo explica y tiene **Abrir ajustes**; conviene dejarlo hecho al instalar la app (Ajustes › Aplicaciones › Acceso especial › Instalar apps desconocidas › Xuper). Al volver de Ajustes continúa solo (o con **Continuar**).
- **Versionado**: `app/build.gradle.kts` toma `-PxuperVersionCode` (el workflow pasa `github.run_number`) para `versionCode` y `versionName = 0.1.<N>`. Sin ese parámetro (Android Studio) queda `1`/`0.1.0`. **No renombrar ni recrear el workflow**: el contador `run_number` se reinicia y los APK nuevos tendrían un número menor que los ya instalados (no se actualizarían).
- **Firma**: debe ser siempre `app/xuper.keystore` (Android rechaza instalar encima con otra firma). No cambiar ni regenerar el keystore.
- **Publicar una versión** (`.github/workflows/build-apk.yml`): un push a `main` solo compila y deja el APK como artefacto. **Se publica como actualización únicamente** si (a) el mensaje del commit incluye `[release]` (el resto del mensaje pasa a ser las "Novedades" del cartel) o (b) se lanza el workflow a mano con **publicar** tildado. Así los commits de prueba no llegan a las teles.
- **Configuración en GitHub** (una vez):
  - Repo de releases público (o este mismo si es público): nada más que hacer; `UPDATE_REPO` por defecto es `github.repository`.
  - Repo principal **privado**: crear un repo aparte (público o privado, con al menos un commit, p. ej. un README) solo para releases, y definir la **variable** `UPDATE_REPO = usuario/repo-de-releases` y el **secret** `UPDATE_TOKEN` (fine-grained token con *Contents: Read and write* sobre ese repo). Si el repo de releases también es privado, agregar el secret `UPDATE_READ_TOKEN` (token de **solo lectura** sobre ese repo): se empaqueta en el APK como `BuildConfig.UPDATE_TOKEN` y se usa para consultar y descargar.
- **Migrar de repo sin tocar las teles instaladas** (p. ej. pasar de un repo público a uno solo de APK y dejar el principal privado). El `UPDATE_REPO` queda grabado en el APK al compilar, y las teles instaladas miran el repo viejo hasta que reciban un APK nuevo. Pasos: (1) crear el repo nuevo (con README) y cargar `UPDATE_TOKEN` + variable `UPDATE_REPO = usuario/repo-nuevo` (+ `UPDATE_READ_TOKEN` si es privado); (2) crear la variable `LEGACY_UPDATE_REPO = usuario/repo-viejo` (este mismo repo); (3) publicar con `[release]`: el workflow publica el release en el repo nuevo y, **después**, también en el viejo (paso "Publicar también en el repo anterior"); (4) las teles se actualizan desde el viejo y a partir de ahí miran el nuevo; (5) **solo cuando todas las teles tengan esa versión**: borrar `LEGACY_UPDATE_REPO` y recién entonces poner privado el repo viejo. Una tele que no se actualizó antes del paso 5 queda mirando un repo privado (404) y habrá que reinstalarla a mano.
- **Advertencias de seguridad**: un release público hace que **el APK sea descargable por cualquiera**, y el APK lleva adentro `assets/` (listas M3U, posibles cuentas IPTV), la clave de TMDB. Si las listas tienen credenciales, usar un repo de releases privado con `UPDATE_READ_TOKEN`. Quien tenga acceso de escritura al repo de releases puede publicar un APK, aunque Android solo lo instalará si está firmado con el keystore del proyecto (cuya contraseña está en `app/build.gradle.kts`: si el repo es público, esa protección no sirve). Activar verificación en dos pasos en GitHub.
- **Límites**: Android no permite instalar en silencio una app que no es del sistema; siempre hace falta que alguien apriete OK en la tele (en Android 12+ puede omitir la confirmación si Xuper fue quien instaló la versión anterior, pero no está garantizado). Sin internet o sin repo configurado, simplemente no pasa nada. Logcat: `adb logcat -s XuperUpdate`.
- **No verificado**: compilación del APK ni instalación real en una TV (se comprobó la sintaxis del Kotlin y del YAML con tree-sitter/PyYAML). Lo más probable que haya que ajustar en la TV: que la pantalla "Instalar apps desconocidas" abra bien en ese modelo, y el foco del cartel con el mando.

## Sección +18: catálogo dinámico + reproductor embebido (13/11/2026)

- **Reemplaza a `xxx.m3u`**: se borró `assets/contenido_oculto/xxx.m3u` y todo lo que dependía de él (`M3uRepository.searchAdultSources`/`isAdultPlaylistTitle`, la búsqueda de listas +18 en `SearchScreen`, el filtro de `PlaylistBrowserScreen`, la lógica `isAdultPlaylist` de `MainActivity.buildRepository` y el refresco de listas al entrar en +18). No quedan dos sistemas en paralelo. Las demás listas M3U, el catálogo normal y el buscador no se tocaron.
- **Datos** (`data/AdultCatalogService.kt`): pornhub.js es de Node.js y no corre en Android, así que se **portó a Kotlin con Jsoup** (ya era dependencia) lo mismo que hacen `videoList` y `searchVideo` de pornhub.js v1.15.0: `GET https://www.pornhub.com/video?o=<orden>&page=N` y `/video/search?search=<texto>&page=N`, leyendo `li.videoblock` (`.linkVideoThumb` → título/`viewkey`, `img` → miniatura con `data-mediumthumb`/`data-src`/`src`, `.duration`, `.views var`, `.hd-thumbnail`) y `li.page_next` para saber si hay más páginas. Órdenes: Populares `ht`, Recientes `cm`, Más vistos `mv`, Mejor valorados `tr`, Más largos `lg`. Pide con las cookies `accessAgeDisclaimerPH=1`, `platform=pc`. Se saltan los videos solo-Premium. **Solo metadatos**: no extrae M3U8/MP4/cookies/streams. Si el sitio cambia su HTML hay que ajustar los selectores (mirar `parseVideos`); alternativa si hiciera falta un backend: un Node chiquito con `pornhub.js` que devuelva el mismo JSON.
- **Pantalla** (`ui/screens/AdultScreen.kt`): fila de categorías, buscador independiente (Enter/Buscar del teclado), grilla de tarjetas 16:9 (miniatura, duración, HD, título, vistas) y **CARGAR MÁS (página N)**. El estado (categoría, búsqueda, páginas cargadas) se conserva al volver del reproductor (`AdultSession`, solo memoria) y se borra al bloquear el perfil +18.
- **Reproductor** (`ui/screens/AdultEmbedPlayerScreen.kt`, ruta `adult-player/{viewkey}/{titulo}`): WebView con el embed oficial `https://www.pornhub.com/embed/{viewkey}`, dentro de la app. Mando: **OK** = reproducir/pausar (JS sobre el `<video>`; si todavía no hay `<video>`, un toque real al centro), **◀ ▶** = ±10 s (mantener = ±30 s), **ATRÁS** = vuelve al catálogo. Las flechas se consumen en Compose, así que no se puede quedar atrapado en la página; la navegación del WebView solo permite `https://www.pornhub.com/embed/*` y no hay popups. No pasa por "Continuar viendo" ni se guarda historial.
- **Perfil +18**: sin cambios (check en PERFIL con PIN, `AdultContentSettings`). La sección y el catálogo solo existen con el perfil desbloqueado; `AdultCatalogService` se niega a consultar si está bloqueado, y el reproductor se cierra solo si se bloquea mientras se mira.
- **No verificado**: compilación del APK, la consulta real al sitio desde la TV (selectores y que no pida verificaciones extra según la IP/país), que el embed arranque con OK en el WebView de la TV, y el foco con el mando. Solo se comprobó la sintaxis Kotlin (tree-sitter). Pantalla completa del propio botón del embed: no manejada (el WebView ya ocupa toda la pantalla).

## Límites y verificación pendiente

- `index.m3u` es una lista grande (aprox. 23.800 líneas en esta copia); se carga en segundo plano con el límite de concurrencia ya existente. Puede tardar más que listas pequeñas.
- El navegador muestra todas las categorías que `M3uRepository` haya podido parsear; una URL caída o una lista con formato no compatible seguirá sin entradas y queda registrada en Logcat (`XuperM3U`).
- El cambio de seek reduce una causa conocida de bloqueos, pero la pantalla negra tras saltos debe verificarse con los streams concretos que fallaban en la TV. No se reemplazó ni rediseñó la UI del reproductor.


### Subtítulos: fila "Idioma", VideoAPI sin clics y saltos (13/11/2026)

- **Ajuste de reproducir (INICIO)**: se conserva el menú de siempre. Debajo de *Subtítulo ON/OFF*, la fila *Pistas de subtítulos* pasó a ser **Idioma** con solo **Español / Inglés** (◀ ▶ elige, OK aplica; `applyLabSubtitleLanguage` limpia elecciones manuales y fija el idioma preferido). (OpenSubtitles ya no existe en la app.)
- **VideoAPI sin clics** (`HomePlayerScreen.derivedVideoApiSubtitles`): del HLS crudo (`https://vimeos.zip/hls2/03/00015/<id>_,n,h,.urlset/master.m3u8?...&srv=s1`) se arma `https://s1.vimeos.net/vtt/03/00015/<id>_spa.vtt` (`hls2`→`vtt`, host = `srv` + `.vimeos.net`, nombre = antes del primer `_`). Se prueban `spa, lat, eng, por, fre, ita, ger` y solo se cargan los que empiezan con `WEBVTT`. Con esos confirmados se espera solo 3 s extra por CineSrc. Dominio fijo `vimeos.net` (según el ejemplo real). Logcat: `XuperSubtitles`.
- **Pantallas negras**: `data/SafeSubtitleMediaSourceFactory.kt` carga cada subtítulo externo con `treatLoadErrorsAsEndOfStream` y 1 reintento (en Media3 1.3.1 un subtítulo lento/caído frenaba o cortaba todo el video). Vigilante de saltos en `PlayerScreen` (6 s re-pide, 14 s reconstruye, 35 s avisa), el salto no cae en el último segundo, buffer de arranque menor en VOD, reintentos de segmentos más rápidos y timeouts HTTP 12/20 s. Se mantiene `SeekParameters.DEFAULT`.
- **No verificado**: compilación ni prueba en la TV (solo sintaxis Kotlin).
