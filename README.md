# Xuper Quilmes TV — Android TV

App nativa en Kotlin + Jetpack Compose for TV + Media3/ExoPlayer. La experiencia visual ahora está organizada como una app de streaming: portada destacada, pósteres, búsqueda, fichas de contenido, temporadas/episodios, Mi lista y Continuar viendo. Se conserva la paleta azul noche/celeste original de Xuper.

## Cambios de esta versión

- **Barra lateral:** se conserva sin cambios: Inicio, Buscar, **ARGENTINA**, Mi lista, Continuar viendo y Perfil. Las tres listas M3U nuevas se integran al catálogo y al buscador, sin agregar accesos a la barra lateral.
- **Inicio:** primera fila con los canales argentinos (respetando el orden curado), segunda fila Gran Hermano, tercera fila Deportes Argentina. A continuación aparecen las filas de películas y series reproducibles de las tres listas M3U, y después las tendencias, películas/series populares y filas de plataformas que TMDB devuelve para Argentina.
- **Argentina y control remoto:** la sección conserva todos los canales argentinos configurados. Los nueve canales prioritarios quedan primero, después todos los streams de Gran Hermano y luego el resto. CH+/CH− recorre esa cola en bucle sin salir de Argentina.
- **TMDB:** carga imágenes, pósteres, fondos, año, puntuación y sinopsis. El buscador consulta TMDB además de los canales y títulos locales. El catálogo se cachea localmente durante 18 horas para reducir llamadas y carga.
- **Series:** las fichas de series TMDB cargan temporadas y episodios reales desde la API; permite cambiar de temporada y seleccionar episodios. Si una lista M3U contiene un episodio con serie, temporada y número coincidentes, aparece un botón para reproducir su URL real. Se muestran sinopsis, imágenes de episodios y duración cuando TMDB la ofrece.
- **Reproductor estilo Disney+:** se reemplazó el controlador visual anterior por una interfaz propia con reproducción/pausa, saltos de 10 segundos, barra de progreso, tiempo restante, selección de audio/subtítulos cuando el stream ofrece esas pistas y controles que se ocultan solos. El botón “Cambiar reproductor” recorre enlaces alternativos; si una fuente falla, intenta automáticamente otra URL coincidente. En series, “Episodio siguiente” aparece abajo a la derecha cerca del final cuando se detecta el siguiente episodio reproducible. CH+/CH− sigue cambiando canales argentinos.
- **Mi lista / Continuar viendo:** conserva favoritos e historial reciente local.
- **Fuentes M3U activas:** Argentina (`iptv-org`), `argentina-extra.m3u`, VOD General (`VOD.m3u`), Agrupado por contenido (`index.content.m3u`), Películas (`categories/movies.m3u`) y Mickflix (`playlist.m3u`). Las listas VOD se descargan al iniciar y se guardan en caché. Mickflix también se usa como fuente de coincidencias y alternativas, sin crear una nueva opción en la barra lateral. Su contenido reproducible se integra en filas del Inicio y en el buscador; las URLs duplicadas se deduplican en las filas VOD.

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

Continuá el proyecto Xuper Quilmes TV a partir del código de esta carpeta. El usuario quiere una experiencia tipo Netflix, pero con la paleta azul noche/celeste original, no roja. La barra lateral conserva únicamente Inicio, Buscar, ARGENTINA, Mi lista, Continuar viendo y Perfil. Las listas M3U `VOD.m3u`, `index.content.m3u`, `categories/movies.m3u` y `https://raw.githubusercontent.com/mickflix/Mickflix-IPTV/master/playlist.m3u` alimentan las coincidencias de contenido y las alternativas de reproducción sin agregar entradas laterales. Inicio debe mostrar primero los canales argentinos, después Gran Hermano, después Deportes Argentina, después el VOD reproducible de las listas y luego las filas de metadatos de TMDB/plataformas. CH+/CH− debe seguir cambiando canales dentro de la cola argentina; flecha arriba debe despejar el reproductor. No agregues otras listas internacionales salvo que el usuario lo pida; las tres fuentes `jromero88/iptv` se añadieron explícitamente por pedido del usuario.

TMDB aporta imágenes, sinopsis, búsqueda, temporadas y episodios, pero no streams. Si una película TMDB coincide por título con una URL M3U, conectar la URL a su ficha manteniendo metadatos TMDB. Para enlaces duplicados del mismo contenido, exponer alternativas con un botón “Cambiar reproductor” y avanzar automáticamente a la siguiente URL coincidente cuando ExoPlayer reporte un error. Para series, intentar vincular episodios por nombre de serie, temporada y número. No inventar URLs: si no hay coincidencia, dejar reproducción deshabilitada. Un error de reproducción no debe borrar permanentemente una entrada; mostrar reintento. Las temporadas/episodios se pueden seleccionar en la ficha; el reproductor usa Media3 y solo puede mostrar subtítulos/audio/episodio siguiente si el stream real ofrece esas pistas o URLs.

Antes de cambiar, revisá el código real. Después de cada iteración actualizá este README, verificá referencias/imports y, si el entorno no tiene Android SDK/Gradle, decilo con claridad y no afirmes que compiló. Conservá el keystore local existente para que las actualizaciones firmadas puedan instalarse sobre el APK anterior; nunca publiques sus credenciales. Entregá un ZIP completo cuando sea posible.
