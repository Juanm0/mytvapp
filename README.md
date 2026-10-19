# Xuper Quilmes TV — Android TV

## Cambios de esta entrega (por qué no se veía nada + reproductor tipo streaming grande)

**1. "No pude ver ninguna película o serie" — causa real, confirmada en el código (no era la API de TMDB).**
`HomeScreen.kt` (`buildHomeRows`) seguía agregando las filas de TMDB (`Tendencias · Películas`,
`Películas populares`, etc.) a Inicio, a pesar de que el README de la entrega anterior decía que
ya se habían sacado — el código y el README habían quedado desincronizados. El problema de fondo:
esas fichas de TMDB traen **metadata de estrenos actuales** (Tendencias, Populares — lo que esté
sonando ahora en streaming), mientras que nuestras listas M3U son **películas de dominio público
viejas** (Night of the Living Dead, etc.) y un puñado de episodios sueltos. Un estreno de TMDB
prácticamente nunca coincide por nombre con una película de dominio público de los años 40-60, así
que el botón de reproducción quedaba deshabilitado ("Video no disponible") para casi todo lo que se
mostraba en Inicio. Encima, el banner destacado (lo primero que se ve y lo más tentador de tocar)
elegía preferentemente una ficha de TMDB — es decir, el usuario tocaba literalmente lo primero que
veía y nunca andaba.

Arreglo:
- `HomeScreen.kt`: Inicio ya NO muestra filas de TMDB. Solo canales de Argentina + lo que
  realmente viene de las listas M3U con video real (`streamUrl` no vacío). El catálogo de TMDB
  sigue estando en el buscador (ahí sí tiene sentido: el usuario ya entiende que puede no tener
  fuente, lo usa para descubrir, no es lo primero que ve).
- El banner destacado ahora elige siempre un título reproducible de verdad.
- `PosterCard.kt`: las fichas sin fuente de video (las que vas a seguir viendo en el buscador)
  ahora muestran una etiqueta "SIN VIDEO" en la esquina, para que se note *antes* de tocarlas.



**2. Reproductor: la flecha derecha no adelantaba — causa real.**
Izquierda/derecha solo hacían seek cuando los controles estaban OCULTOS (`if (!showControls)`).
Pero los controles arrancan VISIBLES por defecto, con foco en "Pausar" — así que la primera vez
que el usuario tocaba derecha, en vez de adelantar, el foco intentaba moverse al botón "10 s ↷"
(dos toques de derecha + OK para que de verdad avance). Se sentía roto.

Arreglo — reproductor rehecho más parecido a Disney+/Netflix en las funciones que importan:
- **Izquierda/derecha ahora SIEMPRE adelantan o atrasan 10 s**, se vean los controles o no y esté
  el foco donde esté. No hace falta "entrar" a ningún botón primero.
- Aparece un indicador central ("⏪ 10 s" / "10 s ⏩") que confirma el salto y se desvanece solo,
  como en cualquier reproductor de streaming grande.
- Como izquierda/derecha ahora están reservadas para el salto, la fila de botones (Reproducir/
  Pausar, Cambiar reproductor, Audio, Subtítulos) se navega con **arriba/abajo** en vez de
  izquierda/derecha — determinístico, sin depender de que Compose "adivine" el próximo foco.
- Se sacaron los botones de "↶ 10 s" / "10 s ↷" (quedaban redundantes con el salto directo).
- Soporte para teclas físicas de medios del control remoto si las tiene: play/pausa, rebobinar,
  adelantar (además de lo anterior).
- CH+/CH- siguen funcionando igual que antes (zapping de canales de Argentina).

No es una copia literal del código de Disney+ (aunque si queres puedo hacer una) — es una implementación propia con el mismo comportamiento.



**4. Verificación.** Este entorno sigue sin Android SDK/Gradle para compilar el APK real. Pasé los
archivos tocados por el compilador de Kotlin (sin classpath de Android, por lo que no resuelve
`androidx.*`/`media3.*` — eso da "unresolved reference" esperable) y comparé contra un archivo que
no toqué (`DetailScreen.kt`) para confirmar que no hay errores de sintaxis *nuevos*: el nivel de
ruido es idéntico en ambos. No reemplaza una compilación real en Android Studio o GitHub Actions.

**Nota de seguridad:** el `local.properties` de este ZIP sigue trayendo la clave de TMDB en texto
plano, y ya pasó por dos chats distintos. No es grave (es una API key gratuita de solo lectura),
pero si en algún momento vas a subir el repo a algún lado público, regenerala antes en
themoviedb.org.

---

## Cambios de esta entrega (arranque sin tildarse + Inicio con contenido)

**1. Se tildaba al abrir la app — causa real y arreglo.**
La entrega anterior movió a un hilo de fondo el cálculo de las filas de Inicio, pero eso no era lo que
congelaba la app. Había dos problemas más graves:
- `MainActivity` hacía `lifecycleScope.launch { repository.refresh(); ...; setCatalogCategories(categories) }`.
  `lifecycleScope` corre en el **hilo principal** (`Dispatchers.Main`), así que `setCatalogCategories()` se
  ejecutaba ahí. Esa función vinculaba **cada una de las ~400 fichas de TMDB** con las listas M3U
  (`resolveCatalogTitle` -> `findPlayableMovie` / `findFirstPlayableEpisode`), y cada vinculación recorría
  **todas** las entradas M3U compilando ~10 expresiones regulares nuevas por entrada. Eran cientos de millones de
  operaciones en el hilo de la interfaz, justo cuando terminaba de cargar: de ahí el congelamiento. El buscador
  tenía el mismo problema (`registerCatalogTitles` en el hilo principal en cada búsqueda), y también abrir
  una ficha de serie (`findPlayableEpisode` por cada episodio visible) y abrir el reproductor
  (`getNextEpisode` / `getPlayableAlternatives`).
- Las `Regex(...)` se compilaban de nuevo en cada llamada (clasificación, episodios, normalización, orden de
  Argentina, etc.).

Arreglo (`data/M3uRepository.kt` reescrito, `MainActivity.kt`, `SearchScreen.kt`, `LibraryScreen.kt`):
- Todas las `Regex` son constantes compiladas una sola vez.
- Cada entrada M3U guarda precalculados su nombre normalizado (`norm`), su clave de título (`key`, con palabras ya
  separadas) y su info de episodio (`episode`), una sola vez y en segundo plano.
- Las fichas de TMDB **ya no se vinculan todas juntas**: se vinculan de forma perezosa (`find()` / `search()`), solo las
  que se abren o aparecen en una búsqueda, con resultado cacheado.
- `setCatalogCategories()` y `registerCatalogTitles()` ahora se llaman desde `Dispatchers.Default`, nunca desde el hilo principal.
- La copia de archivos de `assets` y la limpieza de cachés (antes en `onCreate`, en el hilo principal) pasaron a `Dispatchers.IO`.
- El catálogo TMDB se carga **en paralelo** con las listas M3U (sale del caché de disco casi al instante), así Inicio
  muestra contenido enseguida en vez de esperar a que bajen las 6 listas.
- El caché de disco de las 6 listas se publica **una sola vez** (antes 6 reconstrucciones seguidas).
- La ruta del reproductor ya no escribe `SharedPreferences` ni recalcula colas/alternativas en cada recomposición.
- Pestaña ARGENTINA: el nombre de cada canal se normaliza una sola vez (antes se normalizaba dentro del comparador del `sortedWith`).

**2. Inicio ya no está vacío.** El filtro de VOD buscaba secciones llamadas `"vod general"`, `"agrupado por contenido"` y
`"peliculas"`, que **no existen** con las fuentes actuales (`Películas gratis`, `Series gratis`, `VOD original`), por eso
Inicio solo mostraba Argentina. Ahora `buildHomeRows` (`HomeScreen.kt`) arma:
1. Argentina (canales), Gran Hermano y Deportes Argentina.
2. **Algo de TMDB** (solo 4 filas de 14 títulos): Tendencias · Películas, Tendencias · Series, Películas populares, Series populares.
   El resto del catálogo (plataformas, etc.) sigue estando solo en el buscador.
3. Las primeras `homeRows` filas de cada fuente VOD (`Películas gratis` 2, `Series gratis` 1, `VOD original` 3), sin repetir URLs.
El banner destacado usa la imagen de fondo (`backdropUrl`) de la primera ficha de TMDB.

**3. Verificación.** Este entorno no tiene Android SDK ni Gradle: el código **no se compiló**. Se pasó por el compilador de
Kotlin 1.9.24 solo para detectar errores de **sintaxis** (no hay ninguno); los errores de tipos/librerías no se pueden
verificar acá. Si GitHub Actions marca un error, mandá el log.

**Regla para el futuro:** nada que recorra las listas completas ni compile `Regex` puede correr en el hilo principal ni dentro
de una composición. `lifecycleScope.launch { }` y `LaunchedEffect { }` corren en Main por defecto: usar `withContext(Dispatchers.Default/IO)`.

---

## Cambios de esta entrega (sobre la del fix de freeze)

**1. Inicio ya no muestra el catálogo completo de TMDB — eso queda para el buscador.**
Antes `HomeScreen` agregaba, después de los canales y el VOD real, las filas editoriales de TMDB (tendencias, populares, plataformas). Se sacaron: Inicio ahora solo muestra Argentina (con Gran Hermano y Deportes separados) y las películas/series que vienen de las listas M3U activas (`buildHomeRows` en `HomeScreen.kt` ya no agrega `tmdbRows`). El catálogo de TMDB se sigue pudiendo buscar por nombre desde el buscador, sin cambios ahí.

**2. "Breaking Bad" y "Prison Break" desaparecieron — causa real, no es un bug de interfaz.**
Se verificaron las listas M3U activas (`vod-original.m3u`, `movies.m3u` y `series.m3u` de `onlinem3u/publicdomainm3u`, y `mickflix/Mickflix-IPTV`): ninguna contiene esos dos títulos ahora mismo. Lo más probable es que antes coincidían con una URL de las listas `jromero88/iptv` que se sacaron en la versión anterior por devolver 404. Además, el código descartaba por completo cualquier ficha de TMDB sin un enlace reproducible encontrado — ni siquiera se podía buscar. Se corrigió eso (`registerCatalogTitles` y `setCatalogCategories` en `M3uRepository.kt` ya no filtran por `streamUrl` no vacío), así que ahora **sí van a aparecer en el buscador** con su ficha completa, pero con el botón de reproducción deshabilitado ("Video no disponible") porque ninguna lista activa tiene un link real para ellos. Si el usuario consigue una URL real para alguno, se puede sumar como entrada nueva a `vod-original.m3u`.

**3. Gran Hermano desapareció — mismo tipo de causa, no hay fuente activa.**
Se revisó `argentina-extra.m3u` (local) y `Mickflix-IPTV/playlist.m3u` (remota): ninguna de las dos trae un canal de Gran Hermano en vivo ahora mismo. El motor que arma la sección Argentina sí busca canales de Gran Hermano en **todas** las fuentes activas automáticamente (no hace falta tocar código para que aparezca si alguna lista llega a tenerlo), pero hoy ninguna lo tiene. No se agregó ninguna URL de Gran Hermano a mano porque no hay una fuente propia verificada para eso — si el usuario consigue un link que funcione, se agrega a `argentina-extra.m3u` y aparece solo.

**4. Reproductor: ergonomía del control remoto.**
- Antes, cuando los controles del reproductor se ocultaban (a los 6 s) y volvían a aparecer con una tecla, el foco quedaba "perdido" fuera de los botones — por eso hacía falta tocar abajo dos veces y después OK para recién poder navegar entre Pausar/Avanzar/Subtítulos. Se corrigió: ahora el botón de Pausar/Reproducir se vuelve a enfocar automáticamente cada vez que los controles aparecen (`PlayerScreen.kt`), así que con un solo toque ya queda un botón enfocado y navegable.
- CH+/CH- (`KEYCODE_CHANNEL_UP`/`KEYCODE_CHANNEL_DOWN`) estaban completamente sin implementar en el reproductor — los parámetros `channelQueue`/`onChangeChannel` llegaban desde `MainActivity` pero nunca se usaban. Se implementaron: cambian de canal dentro de la cola de la sección actual (la misma que ya arma `M3uRepository.getChannelQueue`) y dan la vuelta en bucle (del último canal pasa al primero y viceversa), funcionan se vean o no los controles en pantalla, igual que en cualquier TV.

## Fix de esta entrega: la app se congelaba al abrir

**Síntoma reportado:** al abrir la app, la interfaz se ponía muy lenta o directamente se congelaba (no respondía al control remoto) durante varios segundos.

**Causa encontrada:** `HomeScreen` y `LibraryScreen` calculaban las filas de contenido (`rows`) dentro de un bloque `remember(sections) { ... }`, que se ejecuta de forma síncrona en el hilo principal durante la composición. Ese cálculo recorre **todas** las listas cargadas (Argentina, películas gratis, series gratis, Mickflix, VOD original — varios miles de ítems en conjunto), con filtros, `distinctBy`, `HashSet` y ordenamientos. El problema es que `M3uRepository.refresh()` publica el `StateFlow` de `sections` **una vez por cada una de las 6 fuentes** a medida que van terminando de descargar y parsear, así que ese recorrido pesado se repetía varias veces seguidas, en el hilo principal, justo en los primeros segundos después de abrir la app — exactamente el síntoma descripto.

**Arreglo aplicado:** se movió ese cálculo fuera de la composición. Ahora `rows` es un `State` que se actualiza mediante un `LaunchedEffect(sections)` que ejecuta el cálculo con `withContext(Dispatchers.Default)` (hilo de fondo para trabajo de CPU) y solo publica el resultado cuando termina. La lógica es exactamente la misma que antes (no se cambió ningún criterio de orden ni de agrupación), solo se movió de hilo. Afecta:
- `ui/screens/HomeScreen.kt` → función nueva `buildHomeRows(sections)`.
- `ui/screens/LibraryScreen.kt` → función nueva `buildLibraryRows(...)` (mismo problema al entrar a Argentina / Mi lista / Continuar viendo).

**No verificado por compilación real:** este entorno no tiene Android SDK/Gradle instalado, así que el cambio se revisó a mano (sintaxis, imports, balance de llaves) pero no se compiló. Si GitHub Actions marca error, mandalo para corregirlo.

**Si después de este cambio la app sigue lenta/inestable:** lo más probable es que el cuello de botella esté en otro lado (por ejemplo, descargas de red muy lentas de alguna de las 6 listas M3U, o la cantidad de imágenes que Coil intenta cargar a la vez en las filas). Avisá qué se siente distinto (¿lenta todo el tiempo? ¿se congela en un momento puntual? ¿al reproducir?) para poder enfocar el diagnóstico ahí.


App nativa en Kotlin + Jetpack Compose for TV + Media3/ExoPlayer. La experiencia visual ahora está organizada como una app de streaming: portada destacada, pósteres, búsqueda, fichas de contenido, temporadas/episodios, Mi lista y Continuar viendo. Se conserva la paleta azul noche/celeste original de Xuper.

## Cambios de esta versión

- **Barra lateral:** se conserva sin cambios: Inicio, Buscar, **ARGENTINA**, Mi lista, Continuar viendo y Perfil. Las tres listas M3U nuevas se integran al catálogo y al buscador, sin agregar accesos a la barra lateral.
- **Inicio:** primera fila con los canales argentinos (respetando el orden curado), segunda fila Gran Hermano, tercera fila Deportes Argentina. A continuación aparecen las filas de películas y series reproducibles de las tres listas M3U, y después las tendencias, películas/series populares y filas de plataformas que TMDB devuelve para Argentina.
- **Argentina y control remoto:** la sección conserva todos los canales argentinos configurados. Los nueve canales prioritarios quedan primero, después todos los streams de Gran Hermano y luego el resto. CH+/CH− recorre esa cola en bucle sin salir de Argentina.
- **TMDB:** carga imágenes, pósteres, fondos, año, puntuación y sinopsis. El buscador consulta TMDB además de los canales y títulos locales. El catálogo se cachea localmente durante 18 horas para reducir llamadas y carga.
- **Series:** las fichas de series TMDB cargan temporadas y episodios reales desde la API; permite cambiar de temporada y seleccionar episodios. Si una lista M3U contiene un episodio con serie, temporada y número coincidentes, aparece un botón para reproducir su URL real. Se muestran sinopsis, imágenes de episodios y duración cuando TMDB la ofrece.
- **Reproductor estilo Disney+:** se reemplazó el controlador visual anterior por una interfaz propia con reproducción/pausa, saltos de 10 segundos, barra de progreso, tiempo restante, selección de audio/subtítulos cuando el stream ofrece esas pistas y controles que se ocultan solos. El botón “Cambiar reproductor” recorre enlaces alternativos; si una fuente falla, intenta automáticamente otra URL coincidente. En series, “Episodio siguiente” aparece abajo a la derecha cerca del final cuando se detecta el siguiente episodio reproducible. CH+/CH− sigue cambiando canales argentinos.
- **Mi lista / Continuar viendo:** conserva favoritos e historial reciente local.
- **Fuentes M3U activas:** Argentina (`iptv-org`), `argentina-extra.m3u`, películas de dominio público (`onlinem3u/publicdomainm3u/movies.m3u`), una lista de episodios de dominio público (`series.m3u`) y canales Mickflix. Las antiguas rutas `jromero88/iptv` devolvían 404 al verificarlas el 19/10/2026, por eso se reemplazaron por listas VOD que sí responden. El catálogo TMDB solo muestra títulos con un enlace real encontrado en las listas; TMDB aporta metadatos, nunca URLs de video. Mickflix se clasifica como televisión en vivo, no como VOD, y no agrega entradas a la barra lateral.

## Importante: catálogo no significa video disponible

TMDB es una base de datos de metadatos, imágenes, temporadas y episodios. **No entrega los videos de las películas ni de las series.** Por eso los títulos obtenidos solo de TMDB muestran fichas, temporadas y episodios, pero el botón de reproducción aparece deshabilitado mientras no exista una fuente de video real y autorizada. El buscador usa la búsqueda de TMDB y cada fila de plataforma muestra resultados que la API identifica para la región; no equivale a descargar el catálogo completo de cada servicio ni garantiza disponibilidad actual. No hay una API pública que permita copiar todo el catálogo interno de Netflix.

Para reproducir contenido VOD real, la app usa las URLs que vienen en las listas M3U. Si una película del catálogo TMDB coincide por título con una película reproducible de las listas, se conecta esa URL manteniendo la ficha visual de TMDB. En las series, la ficha intenta vincular cada episodio por título, temporada y número. Si no hay coincidencia, el título conserva su ficha y no se inventa un enlace. Los enlaces de terceros pueden estar caídos, requerir autorización o dejar de funcionar. El reproductor prueba automáticamente otra URL coincidente cuando falla una fuente y ofrece “Cambiar reproductor” para recorrer manualmente las alternativas. Si un enlace abre una película incorrecta sin generar error, el usuario puede cambiar de fuente manualmente. La categoría `movies.m3u` puede incluir canales lineales de películas, no necesariamente películas bajo demanda.

## Configuración de TMDB

La clave ya está en el `local.properties` de este ZIP. Ese archivo está excluido de Git. En GitHub Actions configurá el secreto `TMDB_API_KEY` en **Settings → Secrets and variables → Actions**. No publiques este ZIP, el APK ni la clave: la clave se compila dentro de la app personal. Como fue compartida en el chat, lo más seguro es regenerarla antes de distribuir el proyecto.

## Compilar

1. Abrí la carpeta `proj` en Android Studio.
2. Esperá a que Gradle sincronice el proyecto.
3. Usá **Build → Build APK(s)**, o subí el proyecto a GitHub y ejecutá el workflow **Build APK**.
4. Instalá el APK en Android TV y probá navegación con el control remoto.

El entorno de edición no dispone de Gradle/Android SDK para compilar el APK completo; esta entrega fue revisada estáticamente, pero necesita una compilación en Android Studio o GitHub Actions para confirmar que no queden errores de compilación.

## Archivos clave

```text
app/src/main/java/com/quilmes/xuper/
  MainActivity.kt                      # navegación, fuentes activas y reproductor
  data/M3uRepository.kt                # canales argentinos, caché, búsqueda y cola de episodios
  data/TmdbCatalogService.kt           # filas TMDB, búsqueda, temporadas y episodios
  data/TmdbMetadataService.kt          # metadata de títulos existentes de listas M3U
  ui/screens/MainScreen.kt             # navegación lateral original y perfil
  ui/screens/HomeScreen.kt             # portada y filas horizontales
  ui/screens/DetailScreen.kt           # ficha, temporadas y selector de episodios
  ui/screens/SearchScreen.kt           # búsqueda de TMDB y canales
  ui/screens/PlayerScreen.kt           # reproductor, subtítulos/audio si la fuente los ofrece y CH+/CH-
```

## Prompt de continuidad para la próxima IA

Continuá el proyecto Xuper Quilmes TV a partir del código de esta carpeta. El usuario quiere una experiencia tipo Netflix, pero con la paleta azul noche/celeste original, no roja. La barra lateral conserva únicamente Inicio, Buscar, ARGENTINA, Mi lista, Continuar viendo y Perfil. Las listas M3U de películas/series de dominio público y `https://raw.githubusercontent.com/mickflix/Mickflix-IPTV/master/playlist.m3u` alimentan las coincidencias y alternativas de reproducción sin agregar entradas laterales. No restaures rutas antiguas `jromero88/iptv` sin verificar que ya no devuelvan 404. CH+/CH− debe seguir cambiando canales dentro de la cola argentina; flecha arriba debe despejar el reproductor. No agregues otras listas internacionales salvo que el usuario lo pida.

**Inicio NO debe mostrar filas de TMDB (Tendencias/Populares/plataformas) — esto ya se corrigió una vez porque se había desincronizado el README del código real; si lo tocás, verificá `HomeScreen.kt` directamente, no confíes en versiones viejas de este documento.** Inicio muestra, en este orden: canales argentinos, Gran Hermano, Deportes Argentina, y el VOD reproducible de las listas M3U (streamUrl no vacío). El catálogo completo de TMDB (incluidas Tendencias/Populares) solo aparece en el buscador, donde el usuario ya espera que algunas fichas no tengan video — esas se marcan con una etiqueta "SIN VIDEO" en `PosterCard.kt`. El banner destacado de Inicio siempre tiene que elegir un título con `streamUrl` real, nunca una ficha de TMDB sin resolver.

TMDB aporta imágenes, sinopsis, búsqueda, temporadas y episodios, pero no streams. Si una película TMDB coincide por título con una URL M3U, conectar la URL a su ficha manteniendo metadatos TMDB. Para enlaces duplicados del mismo contenido, exponer alternativas con un botón “Cambiar reproductor” y avanzar automáticamente a la siguiente URL coincidente cuando ExoPlayer reporte un error. Para series, intentar vincular episodios por nombre de serie, temporada y número. No inventar URLs: si no hay coincidencia, dejar reproducción deshabilitada. Un error de reproducción no debe borrar permanentemente una entrada; mostrar reintento. Las temporadas/episodios se pueden seleccionar en la ficha; el reproductor usa Media3 y solo puede mostrar subtítulos/audio/episodio siguiente si el stream real ofrece esas pistas o URLs.

**Reproductor:** izquierda/derecha están reservadas GLOBALMENTE para adelantar/atrasar 10 s (siempre, se vean los controles o no); no las uses para navegar foco entre botones. La fila de botones (`PlayerScreen.kt`) navega con arriba/abajo mediante `focusedButton` + `buttonFocusRequesters`, por índice explícito, no por búsqueda geométrica de foco. Si agregás o sacás botones de esa fila, actualizá `buttonCount` y la lista de `FocusRequester`.

REGLAS DE RENDIMIENTO (obligatorias): no ejecutes en el hilo principal nada que recorra las listas completas, ni compiles `Regex` dentro de funciones que se llaman por cada entrada; usá las constantes y los campos precalculados de `Entry` (`norm`, `key`, `episode`) en `M3uRepository.kt`. `lifecycleScope.launch` y `LaunchedEffect` corren en Main: mové el trabajo pesado con `withContext(Dispatchers.Default/IO)`. Las fichas TMDB se vinculan a un stream de forma perezosa (`find()`/`search()`); no las vincules en bloque.

Antes de cambiar, revisá el código real. Después de cada iteración actualizá este README, verificá referencias/imports y, si el entorno no tiene Android SDK/Gradle, decilo con claridad y no afirmes que compiló. Conservá el keystore local existente para que las actualizaciones firmadas puedan instalarse sobre el APK anterior; nunca publiques sus credenciales. Entregá un ZIP completo cuando sea posible.


## Controles del reproductor Android TV

- Flechas izquierda/derecha: salto de 10 segundos; al mantener pulsado, acelera progresivamente a 20, 30 y hasta 60 segundos por repetición del mando.
- Arriba/abajo: muestra el panel y permite recorrer sus botones; Back oculta el panel y, si ya estaba oculto, sale del reproductor.
- OK/Select: activa el botón enfocado; si el panel está oculto, reproduce/pausa y lo vuelve a mostrar.
- Play/Pause y Stop: alterna reproducción/pausa; Stop detiene y vuelve a la pantalla anterior.
- CH+/CH-: cambia de canal en la cola disponible.
- Volumen +/− y Mute: controla el volumen multimedia del dispositivo.
- El panel se oculta tras 5 segundos sin interacción.

## Stremio

No se incorporó un addon de Stremio de terceros preconfigurado. Para que la app obtenga streams de un addon real, hay que configurar su URL de manifiesto y mapear cada película/episodio a un ID externo compatible (por ejemplo IMDb). Los addons de metadatos no entregan video por sí solos y no es confiable prometer que un addon aleatorio tenga fuentes disponibles. Las URLs DASH/Widevine protegidas siguen requiriendo una licencia autorizada.
