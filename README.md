# Xuper Quilmes TV — app propia para Android TV

App nativa para Android TV hecha con Kotlin, Jetpack Compose for TV y Media3/ExoPlayer. Interfaz estilo streaming con barra lateral fija, navegación con control remoto y listas IPTV M3U.

## Secciones actuales

- **Buscar**: busca en los catálogos cargados.
- **TV EN VIVO**: canales detectados como transmisiones en vivo.
- **PELÍCULAS**: elementos VOD detectados por categoría, nombre o URL en las listas M3U.
- **ANIMACIÓN**: canales o títulos cuyo nombre/metadatos indican anime, dibujos, infantil o animación.
- **DESCUBRIR**: cuadrícula de películas y series VOD encontradas. Si las fuentes actuales solo traen canales en vivo, la sección muestra un mensaje explicativo en vez de inventar contenido.

La navegación lateral ya no crea una entrada por cada lista M3U. Las listas configuradas se combinan en las secciones anteriores.

## Stack

- Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material` 1.0.0)
- Media3 / ExoPlayer (HLS, DASH y MP4)
- Coil para logos e imágenes
- Navigation Compose
- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 24

## Cómo se compila (sin Android Studio)

El workflow `.github/workflows/build-apk.yml` compila el proyecto en GitHub Actions cuando hay un `push` a `main`/`master` o se ejecuta manualmente:

1. Abrir la pestaña **Actions** del repositorio.
2. Entrar al último workflow **Build APK**.
3. Descargar el artifact **XuperQuilmesTV-apk**.
4. Instalar el APK en la Android TV.

El proyecto conserva `app/xuper.keystore` para firmar actualizaciones con la misma clave. No compartas ese archivo ni la contraseña públicamente.

## Estructura

```text
app/src/main/java/com/quilmes/xuper/
  MainActivity.kt             -> fuentes M3U, repositorio y navegación principal/reproductor
  data/Models.kt              -> Title, Category, Section
  data/ContentRepository.kt   -> interfaz de datos
  data/M3uRepository.kt       -> descarga y análisis M3U, caché y streams fallidos
  ui/components/              -> tarjetas, filas, barra lateral y skeletons
  ui/screens/MainScreen.kt    -> barra lateral y navegación fija
  ui/screens/LibraryScreen.kt -> TV en vivo, películas, animación y descubrir
  ui/screens/SearchScreen.kt  -> buscador
  ui/screens/PlayerScreen.kt  -> reproducción a pantalla completa
```

## Carga y caché

- Al arrancar se lee el caché local y luego se actualizan las listas en segundo plano.
- Cada lista se publica cuando termina de descargarse y analizarse.
- Los canales que fallan durante la reproducción se eliminan del catálogo y del caché.
- Sus URLs se guardan en `unavailable_streams.txt`, por lo que no vuelven a mostrarse en siguientes arranques aunque sigan presentes en la lista original.
- Los enlaces de YouTube y Twitch se filtran porque ExoPlayer no los reproduce directamente.

**Importante sobre la detección de fallos:** la app no hace miles de conexiones de prueba a cada canal al iniciar, porque eso ralentizaría mucho la carga y algunos proveedores rechazan solicitudes de comprobación aunque el video sí reproduzca. Un stream se marca como no disponible cuando ExoPlayer informa un error real durante el intento de reproducción. La detección no puede garantizar que un canal vaya a funcionar en todas las redes o regiones.

## Reproductor

- Los controles quedan visibles y no se ocultan automáticamente.
- Los controles disponibles incluyen pausa y saltos de ±10 segundos; en streams en vivo la barra de búsqueda puede no funcionar si la transmisión no tiene buffer de retroceso.
- Si ExoPlayer informa un error, el elemento se oculta del catálogo y queda registrado como no disponible.
- Los subtítulos solo pueden seleccionarse si la fuente de video incluye pistas compatibles. La velocidad de reproducción y otras opciones avanzadas pueden ampliarse en futuras versiones.

## Fuentes IPTV

Las fuentes se configuran en `buildRepository()` dentro de `MainActivity.kt`. Se agregó **Canales internacionales**, una lista local incluida en `app/src/main/assets/iptv-extra.m3u`, preparada a partir del listado compartido. Incluye canales en vivo, noticias, deportes, cámaras, radios y algunos enlaces de contenido grabado. La app no necesita descargar esta lista desde un servidor externo; las demás fuentes siguen actualizándose por internet.

La lista original estaba pegada como Markdown, así que se normalizaron sus enlaces y metadatos. Se excluyeron entradas Kodi `plugin://`, YouTube/Twitch que ExoPlayer no reproduce directamente y las entradas de Mediaset explícitamente marcadas como dependientes de VPN. Los demás enlaces no se consideran verificados: la disponibilidad depende de cada proveedor, región y formato. Si ExoPlayer confirma un error al reproducir, la app aplica el mecanismo existente para ocultar ese stream.

La detección de VOD se basa en metadatos M3U, nombres de grupo, términos como película/serie/episodio/conferencia y extensiones de URL comunes. No todas las listas M3U contienen películas o series; las listas de canales abiertos suelen ser solo televisión en vivo.

## Próximas mejoras posibles

- Selector de velocidad de reproducción y selector de subtítulos más visibles.
- Favoritos y continuar viendo.
- Guía de programación (EPG) si la fuente la ofrece.
- Integración con una fuente VOD autorizada o con Xtream Codes.
- Reintentar streams fallidos o permitir restablecer manualmente la lista de streams marcados como no disponibles.

> El entorno de edición no incluye Android SDK ni wrapper de Gradle, por lo que la compilación final debe verificarse en GitHub Actions. Si falla, revisar el log de compilación de Actions.
