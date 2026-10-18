# Xuper Quilmes TV — app propia (desde cero)

App nativa para Android TV, estructura inspirada en Netflix (banner
destacado arriba + filas horizontales de pósters navegables con el
control remoto) pero con identidad propia: tema azul en vez de rojo,
mismo logo del gato con anteojos y mismo nombre de siempre.

A diferencia de los APKs clonados que veníamos modificando, esta app
**no depende de ningún backend ni certificado ajeno** — no hay sistema
de licencias ni chequeo de integridad de terceros que pueda bloquearla.

## Stack
- Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material`) — foco y
  navegación con D-pad ya resueltos por la librería.
- Media3 / ExoPlayer — reproduce HLS, DASH y mp4 directo (sirve tanto
  para IPTV como para streams propios).
- Coil — carga de imágenes (pósters/backdrops) desde URL.
- Navigation Compose — Home → Player.

## Cómo abrirlo
1. Abrir la carpeta del proyecto en Android Studio (versión reciente,
   con el plugin de Android/Kotlin actualizado).
2. Dejar que Gradle sincronice (bajará las dependencias la primera vez).
3. Correrlo en un emulador de Android TV o en un dispositivo TV real
   por ADB (`adb install` del APK generado, o "Run" directo desde
   Android Studio con el dispositivo conectado).

No lo pude compilar a `.apk` en este entorno porque acá no hay Android
SDK instalado (solo las herramientas de línea de comandos que usamos
para firmar los APKs clonados) — hace falta Android Studio o el SDK
completo para generar el instalable.

## Estado actual
- Pantalla Home con banner destacado + 4 filas de ejemplo (Populares,
  Canales en vivo, Series, Recientes).
- Pantalla de reproducción funcional (Media3), usando un stream HLS de
  prueba público.
- Datos 100% mock (`MockContentRepository`, en `data/`) — imágenes de
  picsum.photos y un único stream de prueba para todo.

## Cómo conectar tus datos reales
Todo el contenido pasa por una sola interfaz:

```kotlin
interface ContentRepository {
    suspend fun getFeatured(): Title
    suspend fun getCategories(): List<Category>
}
```

Para conectar una lista IPTV (M3U o Xtream Codes) o tu propio backend:

1. Crear una clase nueva en `data/` que implemente `ContentRepository`
   (ej. `XtreamCodesRepository`, `M3uRepository`).
2. Parsear tu fuente y mapearla a `Title`/`Category` (están en
   `data/Models.kt`).
3. Cambiar una sola línea en `MainActivity.kt`:
   ```kotlin
   private fun buildRepository(): ContentRepository = MockContentRepository()
   // -> reemplazar por tu implementación real
   ```

El resto de la UI (Home, filas, reproductor) no necesita tocarse —
consume `Title`/`Category` sin saber de dónde salieron.

## Pendiente / ideas para seguir
- [ ] Reemplazar `MockContentRepository` por la fuente real (IPTV o
      backend propio).
- [ ] Pantalla de detalle intermedia (sinopsis completa, temporadas) si
      hace falta antes de reproducir.
- [ ] Guardar "seguir viendo" / posición de reproducción.
- [ ] Búsqueda.
- [ ] Perfiles / control parental, si hace falta.

## Compilar el APK sin Android Studio
El repo incluye `.github/workflows/build-apk.yml`. Subiendo el proyecto a
un repositorio de GitHub, cada `push` compila el APK en la nube:
Actions → último "Build APK" → Artifacts → `XuperQuilmesTV-debug-apk`.
También se puede disparar a mano con "Run workflow".
El APK es de tipo debug (firmado con clave de debug), instalable directo
en la TV por ADB o con una app de archivos.
