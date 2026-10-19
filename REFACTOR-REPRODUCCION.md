# Refactor de reproducción y navegación TV

## Catálogo bajo demanda
- Películas y series del catálogo usan FrameXTV como única fuente de reproducción por defecto.
- Películas/series: `https://framextv.xyz/embed/{TMDB_ID}?autoplay=1&muted=0`.
- Episodios: `https://framextv.xyz/embed/{TMDB_ID}/{temporada}/{episodio}?autoplay=1&muted=0`.
- Se eliminó el editor QR de fuentes web, el servidor local para editar URLs desde el móvil y las plantillas de proveedores alternativos que se habían agregado durante las pruebas.
- Las fichas del catálogo siempre muestran Reproducir. La navegación de canales IPTV en vivo conserva el reproductor nativo.

## Control remoto
- El WebView del reproductor traduce las flechas del D-pad a navegación espacial por controles enfocables y OK a selección/click.
- Los controles enfocados reciben un contorno celeste visible.
- El catálogo usa `PosterCard` también en recomendaciones, con el mismo escalado y borde de foco.

## Rendimiento
- Se quitó el sondeo JavaScript cada tres segundos y el selector de servidores duplicado por Xuper; FrameXTV administra sus servidores dentro de su propio reproductor.
- Se mantiene el filtro web existente y la liberación del WebView al salir.

## Validación
- Se verificó la integridad del ZIP resultante y se revisaron referencias a las clases retiradas.
- No se ejecutó una compilación Gradle/Android en este entorno porque el proyecto no incluye `gradlew` y no hay una instalación de Gradle/Android SDK disponible.
