# Xuper Quilmes TV — app de streaming para Android TV

Aplicación nativa para Android TV hecha con Kotlin, Jetpack Compose for TV y Media3/ExoPlayer. El objetivo de producto es una experiencia tipo Netflix: inicio con contenido destacado, catálogo por pósteres, búsqueda, películas y series bajo demanda; la TV en vivo queda como sección complementaria.

> **Importante:** una lista M3U solo describe contenido y direcciones de reproducción; no descarga ni almacena automáticamente las películas. El video se reproduce desde el servidor de origen. Usá fuentes propias, autorizadas o de dominio público. No asumir que una lista IPTV de canales contiene películas o episodios bajo demanda.

## Estado actual (iteración de Argentina + VOD)

- **Inicio:** pantalla inicial tipo Netflix; prioriza películas y series VOD cuando hay títulos disponibles.
- **ARGENTINA:** combina la lista pública de canales argentinos con `argentina-extra.m3u`, que incluye los canales del fragmento del usuario y los canales deportivos. La fila prioriza nueve canales (TN, La Nación/LN+, Crónica, El Trece, Telefe, América, C5N, Canal 9 y TV Pública), luego coloca todas las entradas llamadas Gran Hermano una junto a otra y después el resto. Es un orden manual de favoritos, no una medición en vivo de audiencia. Los enlaces se conservan para probarlos, pero no se garantiza que sigan activos.
- **Mi lista:** se agregó el fragmento M3U que pegaste como fuente local, conservando los grupos (24/7, Animation, Cine, Religioso y Radio). No se considera VOD solo porque un canal esté en el grupo “Cine”.
- **TMDB opcional:** se configura una sola vez con una clave API propia en `local.properties`. Enriquece títulos VOD reales clasificados como MOVIE/SERIES con póster, fondo, sinopsis, año y puntuación; usa una coincidencia conservadora, conserva la URL M3U original y guarda metadata en caché. No crea enlaces de reproducción ni agrega títulos que no estén en las fuentes.
- **OTROS PAÍSES:** agrupa las demás listas de canales. El zapping de Argentina no debe saltar a otros países.
- **Cambio de canal:** en el reproductor, flecha arriba oculta los controles para despejar la imagen. Las teclas dedicadas CH+/CH- recorren la cola argentina en orden y no salen de ella. La compatibilidad depende de que el control remoto exponga esos keycodes; probar en dispositivo real.
- **Catálogo VOD:** se conservan las fuentes separadas de películas y series de dominio público. También se agregó `Lista extendida (experimental)`, importada del archivo de 9.106 líneas que aportó el usuario. Es una lista mixta internacional con canales, cámaras, radios y algunos enlaces VOD/episodios antiguos; no es un catálogo limpio y varios enlaces pueden haber caducado. Se desactiva el enriquecimiento TMDB automático para esa fuente grande, para no disparar cientos de consultas ni retrasar la carga.
- **SERIES:** muestra entradas clasificadas como series/episodios y agrupa episodios por temporada cuando el nombre contiene patrones como `S01E02`, `1x02` o `Temporada 1`. Si la fuente no aporta esos datos, no se inventan temporadas ni sinopsis.
- **Fichas de contenido:** al seleccionar una película/serie se abre una ficha con póster, fondo, nombre, tipo, descripción disponible, indicador de temporada/episodio cuando se puede inferir y botones Reproducir y Favoritos. Las fichas de TV en vivo siguen abriendo directamente el reproductor.
- **Favoritos:** se guardan localmente con SharedPreferences y aparecen en MIS FAVORITOS.
- **Continuar viendo:** se registra el contenido VOD abierto recientemente y se muestra en CONTINUAR VIENDO. Por ahora guarda historial reciente, no posición exacta de reproducción; el progreso por timestamp queda pendiente.
- **Pósteres:** las tarjetas VOD usan formato vertical y recorte tipo streaming; los canales en vivo mantienen formato apaisado y ajuste completo de logo. La fuente `tvg-logo` se reutiliza si existe; si no, se muestra una alternativa con iniciales, sin inventar carátulas.
- **ANIMACIÓN:** filtro transversal por términos de anime, dibujos, infantil y animación.
- **DESCUBRIR:** cuadrícula de elementos no clasificados como TV en vivo.
- **Reproductor:** Media3/ExoPlayer con HLS, DASH y MP4.
- **Caché:** carga el catálogo local al iniciar y actualiza fuentes en segundo plano.
- **Streams fallidos:** cuando ExoPlayer confirma un error, la entrada se oculta y su URL se registra localmente.
- **Límite importante:** no se verificó reproducción de todos los enlaces ni se pudo compilar un APK completo en el entorno de edición. Validar en Android Studio/GitHub Actions y dispositivo real.

## Clasificación de contenido

`data/M3uRepository.kt` clasifica cada entrada mediante `ContentKind` (`LIVE`, `MOVIE`, `SERIES`, `OTHER_VOD`). La lógica es deliberadamente conservadora: no clasifica un canal como película solo porque su grupo se llame “Películas” o porque el nombre contenga “cine”. Busca señales más fuertes como rutas `/movie/` o `/series/`, extensiones de archivo de video, etiquetas VOD y patrones de temporada/episodio (`S01E02`, `1x02`, `Temporada 1`, etc.).

Esta clasificación es heurística, no una garantía. Algunas listas M3U son solo canales en vivo, otras contienen VOD y otras mezclan ambos. Si una fuente no incluye entradas VOD reales, las secciones Películas y Series pueden quedar vacías; la app debe mostrar ese estado honestamente y nunca inventar títulos. La agrupación de episodios por serie, temporadas, sinopsis, año y metadatos enriquecidos requiere que la fuente aporte esa información o que se integre un catálogo/backend legítimo.

## Fuentes configuradas

Las fuentes actuales se configuran en `buildRepository()` dentro de `app/src/main/java/com/quilmes/xuper/MainActivity.kt`. Incluyen la lista pública argentina, `argentina-extra.m3u` (canales argentinos de la lista adjunta y los dos fragmentos escritos por el usuario), listas públicas de canales, `mi-lista.m3u`, `iptv-extra.m3u`, las listas VOD de dominio público y `lista-extendida.m3u` con el archivo completo aportado. `Argentina · agregados` se fusiona dentro de la fila Argentina y no aparece como sección separada. La lista extensa sí aparece en la barra lateral como experimental. Los enlaces pueden caducar o no funcionar en la región; no se verificó reproducción de cada uno.

La lista compartida `https://raw.githubusercontent.com/srfarias/Arg-IpTv/master/m3u` puede evaluarse como una fuente adicional, pero **no debe tratarse automáticamente como catálogo de películas y series**: primero hay que revisar su estructura y confirmar si contiene VOD real o únicamente canales lineales de cine/series. No eliminar fuentes actuales sin comprobar antes su función.

## Configurar TMDB

La clave de TMDB ya quedó cargada en el archivo local `local.properties` de este ZIP, así que no necesitás copiarla manualmente. Ese archivo está incluido en `.gitignore` para evitar que se suba accidentalmente al repositorio. Si Android Studio no detecta tu Android SDK, dejá que configure `sdk.dir` o agregá esa ruta sin borrar `TMDB_API_KEY`.

Para compilar, abrí el proyecto en Android Studio y generá el APK desde **Build → Build APK(s)**. En GitHub Actions, agregá la clave como secreto del repositorio llamado `TMDB_API_KEY` en **Settings → Secrets and variables → Actions**; el workflow ya la lee desde ahí.

**Seguridad:** esta clave quedó en el ZIP personal y se compila dentro del APK. No compartas públicamente el ZIP ni el APK con la clave incorporada. Como la pegaste en el chat, lo más seguro es regenerarla en TMDB antes de distribuir la app. La primera actualización puede demorar mientras se buscan metadatos; los resultados se guardan en caché.

**¿IMDb es más fácil?** IMDb no ofrece una API pública general, gratuita y sin clave que reemplace directamente este uso. Sus datasets públicos sirven para investigación y tablas de títulos, pero no son una API simple de pósteres y sinopsis. OMDb también requiere una API key. Para este proyecto, TMDB sigue siendo la opción más práctica: el registro es una única tarea y luego la app trabaja sola. La integración nunca proporciona el video; solo enriquece los títulos que ya existen en una lista.

## Stack

- Kotlin + Jetpack Compose for TV (`androidx.tv:tv-material` 1.0.0)
- Media3 / ExoPlayer (HLS, DASH y MP4)
- Coil para logos y pósteres
- Navigation Compose
- Gradle Kotlin DSL, AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 24

## Compilación

El workflow `.github/workflows/build-apk.yml` compila el proyecto en GitHub Actions al hacer `push` a `main`/`master` o al ejecutarlo manualmente:

1. Abrir **Actions** en GitHub.
2. Ejecutar o abrir el workflow **Build APK**.
3. Descargar el artifact **XuperQuilmesTV-apk**.
4. Instalar el APK en Android TV y probar con control remoto.

El proyecto no incluye Gradle Wrapper y el entorno inicial de edición no tenía Android SDK instalado; la compilación final debe verificarse en GitHub Actions o en Android Studio. Si falla, corregir el error real que indique el log, no asumir que compila.

## Estructura principal

```text
app/src/main/java/com/quilmes/xuper/
  MainActivity.kt             -> fuentes, repositorio y navegación/reproductor
  data/Models.kt              -> Title, ContentKind, Category, Section
  data/ContentRepository.kt   -> interfaz del repositorio
  data/M3uRepository.kt       -> descarga y análisis M3U, caché y clasificación
  ui/components/              -> tarjetas, filas, barra lateral y skeletons
  ui/screens/MainScreen.kt    -> navegación principal
  ui/screens/LibraryScreen.kt -> TV en vivo, películas, series, animación y descubrir
  ui/screens/SearchScreen.kt  -> buscador
  ui/screens/PlayerScreen.kt  -> reproducción a pantalla completa
  ui/screens/DetailScreen.kt  -> ficha de películas/series, favoritos y reproducción
```

## Seguridad y mantenimiento

- `app/xuper.keystore` es una clave privada de firma. No subirla a repositorios públicos ni compartirla junto con contraseñas. Si se filtra, considerar la clave comprometida.
- Las URLs de streams pueden ser inestables. No realizar cientos de conexiones de prueba al arrancar; eso puede ralentizar la app y generar falsos negativos.
- No borrar automáticamente una URL de la lista original. Los streams fallidos se guardan en `unavailable_streams.txt`; conviene ofrecer más adelante una opción para reintentar o restablecerlos.
- Los enlaces de YouTube/Twitch no se reproducen directamente con la configuración actual y se filtran.
- No prometer descargas offline: actualmente el contenido se reproduce por streaming.

## Próximas prioridades

1. **Netflix-style:** mejorar el destacado y sumar filas “Agregados recientemente” solo cuando haya datos reales.
2. **Modelo de series:** separar serie, temporada y episodio si la fuente lo permite; no inventar temporadas ni sinopsis. Agregar una pantalla de detalle antes de reproducir, con selección de episodio cuando exista información suficiente.
3. **Metadatos:** TMDB está integrado con `TMDB_API_KEY`; seguir los pasos de configuración de abajo, probar con una clave válida y revisar falsos positivos. Mantener separados los metadatos y las URLs reales de reproducción.
4. **Favoritos y continuar viendo:** persistencia local y progreso solo para contenido bajo demanda; el progreso de TV en vivo debe manejarse distinto.
5. **Robustez:** probar M3U con comas dentro de atributos, BOM/encoding, URLs con query strings, fuentes caídas y caché antigua.
6. **Pruebas:** compilar APK, probar D-pad/foco, búsqueda, navegación atrás, reproducción y errores reales en Android TV.

---

## Registro de esta iteración

- La sección **Argentina** combina la fuente pública y un archivo local adicional; ordena nueve canales prioritarios, después todas las señales llamadas Gran Hermano y luego los demás canales.
- Se agregó **Otros países** separado para que el zapping argentino no cambie de país.
- El reproductor oculta los controles con flecha arriba y cambia canales con teclas dedicadas CH+/CH- usando la cola del grupo/fuente actual; el zapping debe quedarse dentro de la categoría/país actual y volver al inicio de esa cola al llegar al final.
- Se retiró de las fuentes activas el pequeño catálogo local de clásicos, porque no coincide con la expectativa de catálogo moderno; se mantienen las fuentes VOD externas.
- Se añadió como fuente local `Mi lista`, con el fragmento M3U que el usuario pegó (24/7, animación, canales de cine, religiosos y radio). Se preservaron URLs y grupos; las URLs no se pudieron comprobar en red desde este entorno.
- Se integró TMDB opcional: la clave se lee desde `local.properties` (`TMDB_API_KEY`), las búsquedas distinguen película/serie, aplican un umbral conservador de similitud, cachean resultados y nunca reemplazan el stream real. Inicio/Películas priorizan por puntuación TMDB si existe. La integración requiere que el usuario configure su propia clave.
- La fila Argentina conserva cada URL distinta de Gran Hermano y las coloca contiguas después de los nueve favoritos. El zapping usa la misma fila y vuelve al principio al llegar al final.
- Se agregó una ficha de contenido con póster/backdrop, metadata disponible, botón reproducir y favoritos.
- Se agregaron pantallas MIS FAVORITOS y CONTINUAR VIENDO con persistencia local. Continuar viendo aún guarda historial de títulos, no el punto exacto del video.
- Las tarjetas de VOD usan proporción vertical y las de TV en vivo conservan formato de logo horizontal.
- Series agrupa por temporada cuando puede inferirla de los nombres de episodios.
- Se amplió la clasificación para detectar episodios etiquetados `EP1`/`EP2` y rutas `/vod`, lo que ayuda a recuperar entradas bajo demanda que ya existían en `iptv-extra.m3u` pero se clasificaban como canales en vivo.
- La URL argentina `https://raw.githubusercontent.com/srfarias/Arg-IpTv/master/m3u` no se activó como VOD porque su descripción habla principalmente de canales premium de películas/series; eso normalmente significa canales lineales, no un catálogo individual bajo demanda. Puede añadirse como fuente de canales si se desea, sin etiquetarla como catálogo VOD.
- No fue posible acceder a Internet desde el entorno de edición ni ejecutar una compilación Android completa. Las URLs y los keycodes de control remoto necesitan validación real.

# PROMPT DE CONTINUIDAD PARA LA PRÓXIMA IA

Estás trabajando sobre el proyecto existente **Xuper Quilmes TV**, una app Android TV nativa Kotlin + Jetpack Compose for TV + Media3/ExoPlayer. El usuario quiere una experiencia principalmente tipo Netflix, con películas y series bajo demanda como foco y TV en vivo como complemento. Debés leer este README y revisar el código real antes de cambiar cosas. No le pidas al usuario que vuelva a explicar el proyecto ni hagas preguntas si podés avanzar con decisiones razonables.

## Estado más reciente que debes respetar

La barra lateral muestra **ARGENTINA**, **OTROS PAÍSES**, **MIS FAVORITOS**, **CONTINUAR VIENDO** y fuentes de listas. Se añadió `Mi lista`, `argentina-extra.m3u` (fragmentos de deportes, noticias, canales principales y entradas argentinas extraídas de la lista extensa) y `lista-extendida.m3u` como fuente experimental. La lista internacional puede contener feeds de Gran Hermano; todas las entradas con ese nombre se ponen juntas después de los nueve canales prioritarios, sin deduplicarlas por marca. No se pudo comprobar la disponibilidad actual de cada URL.

TMDB está integrado en `data/TmdbMetadataService.kt`; configurar la clave siguiendo la sección anterior. Sin clave, no se hacen llamadas. El servicio enriquece MOVIE/SERIES clasificados, no crea enlaces de reproducción, usa coincidencia conservadora y conserva la URL de origen. La lista extensa de 9.106 líneas es mixta y experimental; contiene canales y algunos episodios/VOD antiguos, por lo que sus enlaces pueden estar caídos. Las listas de deportes y noticias del usuario se incorporaron a la fila Argentina. La flecha arriba debe ocultar controles y `KEYCODE_CHANNEL_UP/DOWN` debe recorrer en bucle la cola de la categoría/fuente actual. Verificar en dispositivo real; no afirmar que se compiló si no se ejecutó Gradle/Android SDK.

## Reglas de trabajo

1. **Iterá de forma autónoma:** inspeccioná archivos, identificá errores, implementá un conjunto coherente de cambios y revisá el resultado antes de responder. No entregues solo recomendaciones si podés editar los archivos.
2. **No inventes contenido ni capacidades:** una lista M3U puede ser solo TV en vivo. No presentes canales de cine como películas VOD. No inventes pósteres, títulos, temporadas, sinopsis ni disponibilidad.
3. **Conservá lo que ya funciona:** no elimines fuentes, navegación, estilos o funciones sin necesidad demostrable. Evitá reescribir toda la app por una mejora puntual.
4. **Clasificación:** usá `ContentKind` y corregí la lógica si aparecen falsos positivos/negativos. Diferenciá `LIVE`, `MOVIE`, `SERIES` y `OTHER_VOD`. Las categorías de animación son un filtro transversal, no un tipo incompatible con película/serie.
5. **Fuentes:** la URL `https://raw.githubusercontent.com/srfarias/Arg-IpTv/master/m3u` debe inspeccionarse antes de asumir que contiene películas/series individuales. Preferí fuentes de contenido autorizadas o de dominio público. No reemplaces las listas existentes sin verificar consecuencias.
6. **Netflix-style roadmap:** priorizá Home con contenido VOD destacado, navegación separada para Películas y Series, búsqueda, pantalla de detalle, agrupación real de episodios/temporadas si hay metadatos, favoritos y continuar viendo. No simules funciones que todavía no estén implementadas.
7. **Compatibilidad:** respetá las versiones de dependencias existentes y el foco del control remoto Android TV. Revisá todos los `when`/constructores afectados al agregar campos o categorías.
8. **Pruebas:** buscá errores de sintaxis/imports y ejecutá las comprobaciones disponibles. Si no hay Android SDK o Gradle Wrapper, indicá con precisión que no se pudo compilar localmente y dejá el workflow de GitHub Actions listo para validar.
9. **Seguridad:** `app/xuper.keystore` y sus contraseñas son secretos de firma. No los imprimas ni los subas a un repositorio público. No borres el archivo de la copia local sin explicar el impacto sobre actualizaciones firmadas.
10. **Documentación:** después de cada iteración, actualizá este README, especialmente la sección de estado y este prompt, para que la próxima IA pueda continuar sin depender del historial de chat.
11. **Entrega:** cuando sea posible, devolvé un ZIP completo del proyecto modificado y un resumen corto de cambios, pruebas realizadas y pendientes. No afirmes que compiló si no se comprobó.

## Estado al último cambio

- Se agregó `ContentKind` al modelo `Title`.
- `M3uRepository` ahora clasifica de forma más conservadora para evitar tratar cualquier grupo llamado “Películas” como VOD; detecta patrones comunes de series/episodios y rutas/extensiones de video. Las listas dedicadas VOD aceptan `contentHint` explícito.
- Se agregó la sección lateral **SERIES**.
- Inicio ahora es la pantalla inicial y prioriza VOD sobre TV en vivo; el destacado intenta elegir un título VOD con póster.
- Se agregaron listas separadas de películas y series de dominio público de OnlineM3U, con clasificación forzada por fuente para evitar depender del nombre del grupo M3U.
- Películas, series y Descubrir filtran por tipo de contenido en vez de asumir que todo elemento no-live es una película.
- Pendiente de validar: compilación Android, clave TMDB real y reproducción de enlaces VOD/deportes/Gran Hermano en Android TV. La ficha y favoritos están implementados; Continuar viendo registra títulos recientes, pero no el timestamp exacto. No hay API oficial pública de Netflix para reutilizar su catálogo. TMDB solo proporciona metadatos: hace falta una fuente VOD autorizada que proporcione los videos.
