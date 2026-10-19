# Limpieza de integración retirada y QR

Stremio fue retirado del proyecto: no quedan clases, pantallas ni botones de esa integración. Las fuentes web editables mediante QR siguen siendo parte de `WebSourceRegistry` y `SourceEditServer`; la obtención del IMDb ID se conserva porque permite construir fuentes alternativas para el título, no porque la app use Stremio.

El cambio elimina `StremioAddonApi.kt` y `StremioRepository.kt` en vez de dejarlos como archivos vacíos. Esto evita declaraciones duplicadas y evita que el código obsoleto se compile.
