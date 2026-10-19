# Xuper Quilmes TV — Android TV

App nativa en Kotlin + Jetpack Compose for TV + Media3/ExoPlayer. La experiencia visual ahora está organizada como una app de streaming: portada destacada, pósteres, búsqueda, fichas de contenido, temporadas/episodios, Mi lista y Continuar viendo. Se conserva la paleta azul noche/celeste original de Xuper.

## Cambios de esta versión

- **Barra lateral simplificada:** Inicio, Buscar, **ARGENTINA**, Mi lista, Continuar viendo y Perfil. Se retiraron las otras secciones/listas M3U de la navegación y ya no se cargan las playlists internacionales experimentales en memoria.
- **Inicio:** primera fila con los canales argentinos (respetando el orden curado), segunda fila Gran Hermano, tercera fila Deportes Argentina. Debajo aparecen tendencias, películas/series populares y filas de Netflix, Prime Video, Disney+, Max, Apple TV+, Paramount+, Crunchyroll y MUBI cuando TMDB devuelve resultados para Argentina.
- **Argentina y control remoto:** la sección conserva todos los canales argentinos configurados. Los nueve canales prioritarios quedan primero, después todos los streams de Gran Hermano y luego el resto. CH+/CH− recorre esa cola en bucle sin salir de Argentina.
- **TMDB:** carga imágenes, pósteres, fondos, año, puntuación y sinopsis. El buscador consulta TMDB además de los canales y títulos locales. El catálogo se cachea localmente durante 18 horas para reducir llamadas y carga.
- **Series:** las fichas de series TMDB cargan temporadas y episodios reales desde la API; permite cambiar de temporada y seleccionar episodios. Se muestran sinopsis, imágenes de episodios y duración cuando TMDB la ofrece.
- **Reproductor:** Media3/ExoPlayer mantiene soporte para streams compatibles HLS, DASH y MP4. Los controles se ocultan solos; flecha arriba despeja la imagen. La selección de subtítulos/audio depende de que el stream incluya esas pistas. Si una fuente M3U tiene episodios reproducibles con nombres `S01E02`, `1x02` o `EP2`, puede aparecer “Siguiente episodio” cerca del final.
- **Mi lista / Continuar viendo:** conserva favoritos e historial reciente local.
- **Tamaño:** se quitaron del APK las playlists antiguas internacionales/experimentales; quedan la lista pública de Argentina y `argentina-extra.m3u`.

## Importante: catálogo no significa video disponible

TMDB es una base de datos de metadatos, imágenes, temporadas y episodios. **No entrega los videos de las películas ni de las series.** Por eso los títulos obtenidos solo de TMDB muestran fichas, temporadas y episodios, pero el botón de reproducción aparece deshabilitado mientras no exista una fuente de video real y autorizada. El buscador usa la búsqueda de TMDB y cada fila de plataforma muestra resultados que la API identifica para la región; no equivale a descargar el catálogo completo de cada servicio ni garantiza disponibilidad actual. No hay una API pública que permita copiar todo el catálogo interno de Netflix.

Para reproducir contenido VOD real, conectá una fuente legal/autorizada que incluya URL de cada película/episodio. La lista de canales argentinos sí conserva sus URLs actuales, pero pueden estar caídas, requerir autorización o dejar de funcionar.

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
  ui/screens/MainScreen.kt             # navegación lateral reducida y perfil
  ui/screens/HomeScreen.kt             # portada y filas horizontales
  ui/screens/DetailScreen.kt           # ficha, temporadas y selector de episodios
  ui/screens/SearchScreen.kt           # búsqueda de TMDB y canales
  ui/screens/PlayerScreen.kt           # reproductor, subtítulos/audio si la fuente los ofrece y CH+/CH-
```

## Prompt de continuidad para la próxima IA

Continuá el proyecto Xuper Quilmes TV a partir del código de esta carpeta. El usuario quiere una experiencia tipo Netflix, pero con la paleta azul noche/celeste original, no roja. La barra lateral debe conservar ARGENTINA como única sección de canales y solo utilidades como Inicio, Buscar, Mi lista, Continuar viendo y Perfil. Inicio debe mostrar primero los canales argentinos, después Gran Hermano, después Deportes Argentina y luego las filas de metadatos de TMDB/plataformas. CH+/CH− debe seguir cambiando canales dentro de la cola argentina; flecha arriba debe despejar el reproductor. No vuelvas a agregar las listas internacionales que se eliminaron.

TMDB aporta imágenes, sinopsis, búsqueda, temporadas y episodios, pero no streams. No habilites reproducción falsa para títulos que solo vienen de TMDB. Para contenido real, hace falta una fuente de video autorizada. Las temporadas/episodios se pueden seleccionar en la ficha; el reproductor usa Media3 y solo puede mostrar subtítulos/audio/episodio siguiente si el stream real ofrece esas pistas o URLs.

Antes de cambiar, revisá el código real. Después de cada iteración actualizá este README, verificá referencias/imports y, si el entorno no tiene Android SDK/Gradle, decilo con claridad y no afirmes que compiló. Conservá el keystore local existente para que las actualizaciones firmadas puedan instalarse sobre el APK anterior; nunca publiques sus credenciales. Entregá un ZIP completo cuando sea posible.
