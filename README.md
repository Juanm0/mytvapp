# Xuper Quilmes TV — Android TV

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

Continuá el proyecto Xuper Quilmes TV a partir del código de esta carpeta. El usuario quiere una experiencia tipo Netflix, pero con la paleta azul noche/celeste original, no roja. La barra lateral conserva únicamente Inicio, Buscar, ARGENTINA, Mi lista, Continuar viendo y Perfil. Las listas M3U de películas/series de dominio público y `https://raw.githubusercontent.com/mickflix/Mickflix-IPTV/master/playlist.m3u` alimentan las coincidencias y alternativas de reproducción sin agregar entradas laterales. No restaures rutas antiguas `jromero88/iptv` sin verificar que ya no devuelvan 404. Inicio debe mostrar primero los canales argentinos, después Gran Hermano, después Deportes Argentina, después el VOD reproducible de las listas y luego las filas de metadatos de TMDB/plataformas. CH+/CH− debe seguir cambiando canales dentro de la cola argentina; flecha arriba debe despejar el reproductor. No agregues otras listas internacionales salvo que el usuario lo pida; las tres fuentes `jromero88/iptv` se añadieron explícitamente por pedido del usuario.

TMDB aporta imágenes, sinopsis, búsqueda, temporadas y episodios, pero no streams. Si una película TMDB coincide por título con una URL M3U, conectar la URL a su ficha manteniendo metadatos TMDB. Para enlaces duplicados del mismo contenido, exponer alternativas con un botón “Cambiar reproductor” y avanzar automáticamente a la siguiente URL coincidente cuando ExoPlayer reporte un error. Para series, intentar vincular episodios por nombre de serie, temporada y número. No inventar URLs: si no hay coincidencia, dejar reproducción deshabilitada. Un error de reproducción no debe borrar permanentemente una entrada; mostrar reintento. Las temporadas/episodios se pueden seleccionar en la ficha; el reproductor usa Media3 y solo puede mostrar subtítulos/audio/episodio siguiente si el stream real ofrece esas pistas o URLs.

Antes de cambiar, revisá el código real. Después de cada iteración actualizá este README, verificá referencias/imports y, si el entorno no tiene Android SDK/Gradle, decilo con claridad y no afirmes que compiló. Conservá el keystore local existente para que las actualizaciones firmadas puedan instalarse sobre el APK anterior; nunca publiques sus credenciales. Entregá un ZIP completo cuando sea posible.
