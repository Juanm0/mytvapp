# Xuper Quilmes TV — app propia para Android TV

App nativa para Android TV (estilo Netflix: barra lateral + filas de canales navegables con el
control remoto), tema azul, logo del gato con anteojos. Sin backend ni licencias de terceros:
lee listas IPTV en formato M3U.

## Stack
- Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material` 1.0.0)
- Media3 / ExoPlayer (HLS, DASH, mp4) con controles de reproductor
- Coil (logos), Navigation Compose (Principal → Player)
- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 24

## Cómo se compila (SIN Android Studio)
El repo tiene `.github/workflows/build-apk.yml`. Cada `push` a `main` compila en GitHub Actions:
Actions → último "Build APK" → Artifacts → `XuperQuilmesTV-apk` → instalar el `.apk` en la TV.
- El APK es **release** (más rápido que debug) firmado con `app/xuper.keystore` (clave fija):
  cada APK nuevo se instala **encima** del anterior sin desinstalar.
- Si el build falla: copiar el log rojo de Actions y pegárselo a la IA.

## Estructura
```
app/src/main/java/com/quilmes/xuper/
  MainActivity.kt          -> lista de fuentes (buildRepository) + navegación main/player
  data/Models.kt           -> Title, Category, Section
  data/ContentRepository.kt-> interfaz (sections, loading, refresh, find, search)
  data/M3uRepository.kt    -> descarga/parsea M3U, caché en disco, búsqueda
  ui/theme/Theme.kt        -> colores + LocalContentColor (evita texto oscuro sobre fondo oscuro)
  ui/components/           -> PosterCard, ContentRow, SideBar, SkeletonRows
  ui/screens/              -> MainScreen (barra lateral), HomeScreen, CatalogScreen, SearchScreen, PlayerScreen
```

## Cómo funciona la carga rápida
1. `MainActivity` llama `repository.refresh()` apenas arranca.
2. `refresh()` primero lee el **caché del disco** (`filesDir/catalog_N.tsv`) → la app muestra todo al instante
   desde el 2º arranque en adelante.
3. En paralelo baja las listas; cada una se publica apenas termina (no espera a la más lenta) y se guarda en el caché.
4. Mientras no hay datos se ven filas "fantasma" (`SkeletonRows`), no un cartel de "Cargando".
5. La UI solo observa `repository.sections` (StateFlow).

## Agregar / cambiar listas
Editar `buildRepository()` en `MainActivity.kt`: cada `M3uSource(name, url, icon, homeRows, priorityGroups)`
es un ítem de la barra lateral. Se filtran links de YouTube/Twitch (ExoPlayer no los reproduce).

## Controles del reproductor
OK/Enter muestra los controles (pausa, ±10 s, barra). En canales en vivo sin buffer de retroceso la
barra de progreso aparece bloqueada: es normal. Atrás vuelve a la lista.

## Pendiente / ideas
- [ ] Favoritos y "seguir viendo" (guardar en disco)
- [ ] Filtros dentro de una lista (chips por categoría/país)
- [ ] Reintentar/saltar al siguiente canal cuando uno está caído; ocultar canales caídos
- [ ] Guía de programación (EPG: las listas traen `x-tvg-url`)
- [ ] Soporte Xtream Codes (nueva clase que implemente `ContentRepository`)
- [ ] Activar R8 (`isMinifyEnabled = true` en release) si hace falta más velocidad — puede pedir reglas proguard
- [ ] Optimizar recomposiciones / baseline profile si todavía va lento en TVs viejas

> Importante: el código **nunca se pudo compilar en el entorno de la IA** (no hay Android SDK); se
> valida solo con el build de GitHub Actions. Puede haber errores de compilación; arreglarlos con el log.

---

## PROMPT PARA LA PRÓXIMA IA (copiar y pegar al empezar el chat, junto con el zip del proyecto)

```
Vas a continuar el desarrollo de "Xuper Quilmes TV", una app propia de Android TV (Kotlin + Jetpack
Compose for TV + Media3). Te adjunto el zip del proyecto: leé primero el README.md completo, que
explica arquitectura, estado y pendientes.

CONTEXTO DEL USUARIO
- Habla español rioplatense (Argentina); respondé siempre en español, directo y sin vueltas.
- NO tiene Android Studio ni PC de desarrollo. Edita los archivos subiéndolos/editándolos desde la
  web de GitHub y descarga el APK desde GitHub Actions (workflow .github/workflows/build-apk.yml,
  artifact "XuperQuilmesTV-apk"). Lo prueba en una Android TV real.
- No le gustan las preguntas aclaratorias innecesarias: si ya te dio la información, avanzá con la tarea.
- Cuando algo falla me pega el log de error de Actions o describe qué ve en la TV: arreglalo y
  devolvé los archivos COMPLETOS modificados (no fragmentos sueltos) y/o un zip actualizado,
  indicando exactamente cuáles archivos cambiaron para que pueda reemplazarlos en GitHub.
- Las listas IPTV que usa son públicas (iptv-org y Free-TV) y sirven de contenido de prueba.

ESTADO ACTUAL (resumen)
- Barra lateral expandible estilo Netflix (Buscar, Inicio y una entrada por lista M3U), buscador que
  filtra todas las listas sin tildes, filas de canales con logo sobre fondo claro y nombre en franja oscura.
- Arranque rápido: caché en disco (TSV) + descarga en paralelo publicando cada lista al terminar +
  filas "skeleton" en vez de "Cargando".
- Reproductor ExoPlayer con controles (pausa, ±10 s), redirecciones http<->https, aviso si el canal está caído.
- Build release firmado con app/xuper.keystore (clave fija, contraseña en build.gradle.kts).

REGLAS TÉCNICAS / TRAMPAS CONOCIDAS
- El código NO se pudo compilar localmente nunca: revisá cada import y cada firma de API antes de
  entregar. Usá solo APIs estables de tv-material 1.0.0 (el Card NO tiene parámetro onFocus; el foco
  se maneja con CardDefaults.scale/border). Evitá APIs experimentales.
- tv-material: el color de texto por defecto puede salir oscuro; el tema ya provee LocalContentColor y
  los Text importantes llevan color explícito. No dejes Text sin color sobre fondo oscuro.
- Media3 1.3.1: las APIs de DefaultHttpDataSource/DefaultMediaSourceFactory son @UnstableApi → usar
  @OptIn(UnstableApi::class).
- ExoPlayer no reproduce links de YouTube/Twitch; ya se filtran en M3uRepository.
- Los ids de Title son únicos por lista ("s{n}-{contador}"); se usan como key en LazyRow/LazyColumn
  y como argumento de navegación: no pueden repetirse ni llevar "/".
- Para fuentes nuevas implementá ContentRepository (sections, loading, refresh, find, search) y
  cambiá solo buildRepository() en MainActivity.kt.
- Minimizá el trabajo en el hilo principal (red y parseo van en Dispatchers.IO).

PRÓXIMOS PASOS SUGERIDOS: ver "Pendiente / ideas" del README (favoritos, filtros por categoría,
saltar canales caídos, EPG, Xtream Codes). Empezá preguntándome qué se ve mal o qué falla al probar
la última versión, o seguí con el primer pendiente si no hay errores.
```
