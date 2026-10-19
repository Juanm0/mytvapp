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

`ui/screens/XuperBraveAdblock.kt` integra el motor nativo Rust/JNI `adblock-rust` que usa Brave mediante el AAR oficial `io.github.mlm-games:adblock:0.1.3`. El workflow descarga el AAR de Maven Central como archivo local y ajusta únicamente la metadata `minCompileSdkVersion` de 37 a 36 para que el proyecto pueda usar el SDK 36 disponible en GitHub Actions; el motor nativo y sus clases no se reemplazan por el filtro local. Esta compatibilidad debe confirmarse con una compilación real de CI.

El motor intercepta solicitudes del WebView, carga EasyList, EasyPrivacy y AdGuard Annoyances en segundo plano, cachea las listas y aplica recursos cosméticos CSS/scriptlets cuando están disponibles. `XuperWebRequestFilter.kt` queda como respaldo. No convierte WebView en Chromium/Brave completo y no garantiza bloquear anuncios integrados directamente en un vídeo o servidos desde el mismo origen del contenido.

La librería wrapper está documentada en https://github.com/mlm-games/adblock-rs-kt-wrapper y el motor en https://github.com/brave/adblock-rust.
