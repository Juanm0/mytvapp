# Xuper Quilmes TV — Android TV

Aplicación personal de streaming para Android TV, escrita en Kotlin 2.4.21 + Jetpack Compose for TV. El proyecto separa el catálogo visual, los streams IPTV en vivo y las fuentes web alternativas.

## Experiencia actual

- **Inicio:** catálogo visual estilo streaming con tendencias, películas y series populares, y filas por plataforma en Argentina. La información, las imágenes, las temporadas y las sinopsis vienen de TMDB. Una ficha de TMDB no se considera un vídeo reproducible por sí misma.
- **Argentina:** todos los canales IPTV en vivo, incluidos los canales deportivos y señales argentinas, están en la sección ARGENTINA de la barra lateral; no se duplican en Inicio.
- **Buscar:** búsqueda de películas y series en TMDB y el catálogo local.
- **Mi lista / Continuar viendo / Perfil:** utilidades locales de la aplicación.
- **Ficha de una serie:** selector de temporadas, lista de episodios con título, sinopsis, duración e imagen cuando está disponible. El usuario elige el episodio concreto antes de abrir sus fuentes.
- **Fuentes web alternativas globales:** películas generan el embed de StreamIMDB usando el IMDb ID real consultado en TMDB (`/embed/movie/{imdb_id}/`), más búsqueda en Movies2Watch y búsqueda/catálogo de RePelisHD. Movies2Watch usa rutas internas con slug e identificador propio; Xuper genera una URL candidata de búsqueda (`/?s=...`) a partir del título y no inventa IDs internos. Esa ruta de búsqueda debe verificarse contra el sitio activo. Episodios generan las dos variantes de FrameXTV (`/watch/{TMDB_ID}/{temporada}/{episodio}` y el orden inverso) y búsqueda de Movies2Watch y búsqueda/catálogo de RePelisHD. Al seleccionar un episodio, se actualiza la lista de servidores disponibles para ese episodio; FrameXTV.xyz queda primero como proveedor predeterminado. Cada servidor tiene su propio botón Reproducir, sin exigir pegar URLs con el mando. Las rutas de FrameXTV y RePelisHD son candidatas y no garantizan que el proveedor encuentre o reproduzca ese título.
- **IPTV:** las URL M3U8/MP4 y otros medios directos se reproducen con Media3/ExoPlayer. Las páginas web se abren dentro de WebView, con filtro publicitario local compartido.

## Fuentes web y límites

`data/WebSourceRegistry.kt` centraliza la generación de fuentes. Para películas se utiliza la clave de la ficha y el IMDb ID real que devuelve `TmdbCatalogService.loadImdbId`; el servicio consulta `/{movie|tv}/{tmdb_id}/external_ids` de TMDB y cachea los IDs reales durante 180 días (los resultados sin ID durante 7 días), sin derivarlos ni inventarlos. Para episodios, la clave es `ID_SERIE|sTEMPORADA|eEPISODIO`. Movies2Watch se incorpora como fuente global con búsqueda candidata basada en el título (`https://movies2watch.vc/?s=...`); las fichas observadas usan slugs e IDs propios, así que Xuper no puede construir una ficha específica sin resolver primero el resultado del catálogo. La ruta `/?s=` aún debe probarse en Android TV. La URL que dio el usuario para FrameXTV fue `https://framextv.xyz/watch/1396/3/11`; la lista de servidores del episodio coloca primero `https://framextv.xyz/watch/{TMDB_ID}/{temporada}/{episodio}` y conserva la variante con temporada/episodio invertidos como alternativa. Una búsqueda web encontró el dominio y una advertencia externa de reputación para `framextv.xyz`; tratarlo como proveedor no verificado y no introducir credenciales. RePelisHD recibe una búsqueda de título con temporada/capítulo, además de enlaces a sus catálogos. Los botones Reproducir abren cada candidata dentro de la app; no hace falta escribir ni copiar URLs con el control remoto. Las URLs de fuentes externas se codifican Base64 URL-safe al navegar entre pantallas para evitar que barras, signos de interrogación o ampersands rompan la ruta de navegación. Si Android termina el proceso aislado de WebView, Xuper muestra un error y permite volver sin cerrar toda la Activity. Se añadieron guardas para que errores Java del filtro Brave al interceptar solicitudes o inyectar cosméticos no derriben la navegación; esto no puede capturar fallos nativos del proceso ni sustituye un Logcat del dispositivo si el cierre persiste.

**Estado de verificación:** la plantilla StreamIMDB se construye según el formato explícito que dio el usuario (`/embed/movie/{IMDb_ID}/`) y solo se genera si TMDB devuelve un identificador `tt...`. Las rutas de FrameXTV y la búsqueda de RePelisHD son candidatas; no se ha verificado que cada una resuelva todos los títulos ni que todos sus reproductores permitan reproducción en WebView. Las páginas `lewblivehdplay.ru` y `qqwebplay.xyz` aparecen como cabeceras Referer en una lista IPTV aportada por el usuario; no se tratan como catálogos VOD. El parser M3U conserva las cabeceras HTTP que cada stream necesita.

Los proveedores cambian su estructura y pueden requerir JavaScript, cookies, acceso regional o mecanismos incompatibles con WebView. No se inventan URLs de vídeo directas ni se considera una fuente válida solo por guardarla. Usar únicamente fuentes a las que se tenga acceso legítimo.

## Protección publicitaria basada en Brave

`ui/screens/XuperBraveAdblock.kt` integra el motor nativo Rust/JNI `adblock-rust` que usa Brave mediante el AAR oficial `io.github.mlm-games:adblock:0.1.3`. El workflow descarga el AAR de Maven Central como archivo local y ajusta únicamente la metadata `minCompileSdkVersion` de 37 a 36 para que el proyecto pueda usar el SDK 36 disponible en GitHub Actions; el motor nativo y sus clases no se reemplazan por el filtro local. El wrapper se compila ahora con Kotlin 2.4.21, ya que el AAR 0.1.3 declara metadata Kotlin 2.4.0. Esta compatibilidad debe confirmarse con una compilación real de CI.

El motor intercepta solicitudes del WebView, carga EasyList, EasyPrivacy y AdGuard Annoyances en segundo plano, cachea las listas y aplica recursos cosméticos CSS/scriptlets cuando están disponibles. `XuperWebRequestFilter.kt` queda como respaldo. No convierte WebView en Chromium/Brave completo y no garantiza bloquear anuncios integrados directamente en un vídeo o servidos desde el mismo origen del contenido.

La librería wrapper está documentada en https://github.com/mlm-games/adblock-rs-kt-wrapper y el motor en https://github.com/brave/adblock-rust.

### Ajustes de compilación (octubre de 2026)
- `app/build.gradle.kts` usa `kotlin.compilerOptions` con `JvmTarget.JVM_21`, requerido por Kotlin Gradle Plugin 2.4.x; se eliminó el uso obsoleto de `kotlinOptions.jvmTarget`.
- GitHub Actions usa Gradle 8.14.4 para evitar la advertencia de compatibilidad de Gradle 8.11.1 con futuras versiones de Kotlin.


### Cambios de reproducción (octubre de 2026)
- En la ficha de una serie, el botón de cada episodio selecciona ese episodio para mostrar su lista de servidores en “Fuentes web alternativas”; el botón ya no lanza FrameXTV sin permitir elegir.
- FrameXTV.xyz es la primera fuente generada por defecto para episodios. Su disponibilidad sigue siendo una candidata no verificada.
- Se endureció el manejo de errores Java del filtro publicitario en WebView y la liberación del WebView cuando Android mata su renderer. No se pudo reproducir el cierre sin Logcat ni ejecutar un build Android en este entorno.


## Estabilidad del reproductor y filtro publicitario

Se deshabilitó la integración nativa `adblock-rust`/Brave (JNI), porque un fallo dentro de una biblioteca nativa puede cerrar el proceso de Android sin que la app pueda recuperarse. El proyecto ya no descarga ni enlaza el AAR nativo. El WebView conserva el filtro local `XuperWebRequestFilter` para bloquear dominios y rutas publicitarias comunes. La cobertura es menor que la de las listas completas de Brave, pero evita cargar el motor nativo en el flujo de reproducción. Esta modificación elimina ese componente como posible causa; el cierre solo puede darse por confirmado como resuelto después de probar el APK en el dispositivo.


## Reproducción web integrada (actualización)

- Las URLs web de las fuentes ya no pasan directamente al WebView al tocar Reproducir: primero se consultan con `WebPageMediaResolver`.
- El resolver busca fuentes de vídeo declaradas en HTML (`video`, `source`, metadatos de vídeo) y sigue hasta dos niveles de iframes públicos para intentar encontrar MP4/HLS/DASH que pueda reproducir ExoPlayer.
- Si no hay un stream directo visible, el último reproductor disponible se mantiene dentro de la pantalla de Xuper; no se abre un navegador externo. Los sitios que construyen el stream con JavaScript, requieren sesión o usan DRM pueden no permitir extracción nativa.
- FrameXTV.xyz se incluye primero para todos los episodios/temporadas. También se prueba una ruta por ID TMDB para películas, pero esa ruta es tentativa y no se garantiza que el proveedor la soporte.
- El resolver usa solicitudes HTTP públicas con timeout y límite de profundidad para no hacer una cascada larga de solicitudes.

## Edición de fuentes desde el celular por QR

En la ficha de una película/serie y junto a cada episodio se muestra un QR que abre un editor web servido temporalmente por la app en la red local (puerto 8765). Escanealo con el teléfono conectado a la misma Wi-Fi que el Android TV, elegí la fuente a reemplazar, pegá la URL corregida y guardá: el enlace se almacena en las preferencias locales de Xuper para esa película o episodio. Si el QR no aparece, revisá que el televisor tenga una dirección IPv4 local y que ambos dispositivos estén en la misma red. No es un servicio en Internet y no funciona desde fuera de la red doméstica.
