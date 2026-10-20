# XuperQuilmesTV — siguiente episodio

Entrega incremental: solo reemplaza dos archivos Kotlin del proyecto. No incluye `.git`, configuración de GitHub Actions, listas IPTV, credenciales ni el resto del proyecto.

## Cambios

- Añade un mini-hub de “Episodio siguiente” con miniatura, título, temporada/episodio, cuenta regresiva de 10 segundos y botón para avanzar inmediatamente.
- En el reproductor nativo, muestra el hub al entrar en los últimos 10 segundos y pasa al siguiente episodio automáticamente al finalizar, si existe uno configurado.
- En FrameXTV/VideoAPI, intenta mostrar el hub y avanzar automáticamente cuando el reproductor expone el tiempo actual y la duración al WebView.
- Para episodios de TMDB lanzados desde la ficha, calcula el episodio siguiente; si es el último de la temporada, intenta continuar con el primer episodio de la próxima temporada.
- Mantiene el mismo proveedor seleccionado (FrameXTV o VideoAPI/Latino) al avanzar al episodio siguiente.

## Límite

FrameXTV expone eventos de tiempo mediante su puente y VideoAPI puede exponerlos si el video HTML5 es accesible. Si el proveedor oculta la duración/tiempo dentro de un iframe cross-origin, la app no puede saber con precisión cuándo quedan 10 segundos y no puede garantizar la cuenta regresiva ni el autoplay. La reproducción manual y los controles del proveedor siguen disponibles.

## Archivos incluidos

- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`
