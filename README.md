# Xuper Quilmes TV — app de streaming para Android TV

Aplicación nativa para Android TV hecha con Kotlin, Jetpack Compose for TV y Media3/ExoPlayer. El objetivo de producto es una experiencia tipo Netflix: inicio con contenido destacado, catálogo por pósteres, búsqueda, películas y series bajo demanda; la TV en vivo queda como sección complementaria.

> **Importante:** una lista M3U solo describe contenido y direcciones de reproducción; no descarga ni almacena automáticamente las películas. El video se reproduce desde el servidor de origen. Usá fuentes propias, autorizadas o de dominio público. No asumir que una lista IPTV de canales contiene películas o episodios bajo demanda.

## Estado actual

- **Inicio:** ahora es la pantalla inicial y prioriza filas VOD (películas y series/episodios) antes de TV en vivo; el destacado prioriza un título VOD con póster cuando existe.
- **Buscar:** busca títulos dentro del catálogo cargado.
- **TV EN VIVO:** muestra transmisiones lineales.
- **PELÍCULAS:** muestra entradas clasificadas como película o contenido VOD genérico.
- **SERIES:** muestra entradas reconocidas como series o episodios.
- **ANIMACIÓN:** filtro transversal por términos de anime, dibujos, infantil y animación; puede incluir canales en vivo o títulos VOD.
- **DESCUBRIR:** cuadrícula de todos los elementos no clasificados como TV en vivo.
- **Reproductor:** Media3/ExoPlayer con HLS, DASH y MP4.
- **Caché:** carga el catálogo local al iniciar y actualiza las fuentes en segundo plano.
- **Streams fallidos:** cuando ExoPlayer confirma un error, la entrada se oculta y su URL se registra localmente.

## Clasificación de contenido

`data/M3uRepository.kt` clasifica cada entrada mediante `ContentKind` (`LIVE`, `MOVIE`, `SERIES`, `OTHER_VOD`). La lógica es deliberadamente conservadora: no clasifica un canal como película solo porque su grupo se llame “Películas” o porque el nombre contenga “cine”. Busca señales más fuertes como rutas `/movie/` o `/series/`, extensiones de archivo de video, etiquetas VOD y patrones de temporada/episodio (`S01E02`, `1x02`, `Temporada 1`, etc.).

Esta clasificación es heurística, no una garantía. Algunas listas M3U son solo canales en vivo, otras contienen VOD y otras mezclan ambos. Si una fuente no incluye entradas VOD reales, las secciones Películas y Series pueden quedar vacías; la app debe mostrar ese estado honestamente y nunca inventar títulos. La agrupación de episodios por serie, temporadas, sinopsis, año y metadatos enriquecidos requiere que la fuente aporte esa información o que se integre un catálogo/backend legítimo.

## Fuentes configuradas

Las fuentes actuales se configuran en `buildRepository()` dentro de `app/src/main/java/com/quilmes/xuper/MainActivity.kt`. Incluyen listas públicas de canales, el archivo local `app/src/main/assets/iptv-extra.m3u` y dos listas VOD de dominio público de OnlineM3U: películas y series. Estas dos fuentes tienen `contentHint` explícito para que no dependan de heurísticas de nombre/URL. Las listas públicas pueden cambiar, dejar de funcionar o contener enlaces no disponibles en ciertas regiones. No se consideran verificadas por el mero hecho de descargarse.

La lista compartida `https://raw.githubusercontent.com/srfarias/Arg-IpTv/master/m3u` puede evaluarse como una fuente adicional, pero **no debe tratarse automáticamente como catálogo de películas y series**: primero hay que revisar su estructura y confirmar si contiene VOD real o únicamente canales lineales de cine/series. No eliminar fuentes actuales sin comprobar antes su función.

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
```

## Seguridad y mantenimiento

- `app/xuper.keystore` es una clave privada de firma. No subirla a repositorios públicos ni compartirla junto con contraseñas. Si se filtra, considerar la clave comprometida.
- Las URLs de streams pueden ser inestables. No realizar cientos de conexiones de prueba al arrancar; eso puede ralentizar la app y generar falsos negativos.
- No borrar automáticamente una URL de la lista original. Los streams fallidos se guardan en `unavailable_streams.txt`; conviene ofrecer más adelante una opción para reintentar o restablecerlos.
- Los enlaces de YouTube/Twitch no se reproducen directamente con la configuración actual y se filtran.
- No prometer descargas offline: actualmente el contenido se reproduce por streaming.

## Próximas prioridades

1. **Mejorar la experiencia tipo Netflix:** Home como destino inicial, hero destacado que priorice películas/series VOD, filas “Películas”, “Series”, “Agregados recientemente” cuando haya datos reales y estados vacíos claros.
2. **Modelo de series:** separar serie, temporada y episodio si la fuente lo permite; no inventar temporadas ni sinopsis. Agregar una pantalla de detalle antes de reproducir, con selección de episodio cuando exista información suficiente.
3. **Metadatos:** aprovechar `tvg-logo` como póster cuando exista. Si se integra un proveedor externo de metadatos, hacerlo mediante una API autorizada y mantener separado el catálogo de metadatos de las URLs de reproducción.
4. **Favoritos y continuar viendo:** persistencia local y progreso solo para contenido bajo demanda; el progreso de TV en vivo debe manejarse distinto.
5. **Robustez:** probar M3U con comas dentro de atributos, BOM/encoding, URLs con query strings, fuentes caídas y caché antigua.
6. **Pruebas:** compilar APK, probar D-pad/foco, búsqueda, navegación atrás, reproducción y errores reales en Android TV.

---

# PROMPT DE CONTINUIDAD PARA LA PRÓXIMA IA

Estás trabajando sobre el proyecto existente **Xuper Quilmes TV**, una app Android TV nativa Kotlin + Jetpack Compose for TV + Media3/ExoPlayer. El usuario quiere una experiencia principalmente tipo Netflix, con películas y series bajo demanda como foco y TV en vivo como complemento. Debés leer este README y revisar el código real antes de cambiar cosas. No le pidas al usuario que vuelva a explicar el proyecto ni hagas preguntas si podés avanzar con decisiones razonables.

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
- Pendiente de validar: compilación Android, disponibilidad real de los enlaces VOD en Android TV, pantalla de detalle VOD, agrupación de temporadas/episodios, favoritos/continuar viendo y una opción para restablecer streams marcados como fallidos.
