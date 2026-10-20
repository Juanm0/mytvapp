XuperQuilmesTV — correcciones incrementales

Reemplazar únicamente:
- app/src/main/java/com/quilmes/xuper/MainActivity.kt
- app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt

Cambios:
- Se importa setValue para la variable delegada nextExternalEpisode (error de compilación en MainActivity.kt).
- Se importa Modifier.width (error de compilación en PlayerScreen.kt).
- Las reproducciones embebidas/Latino se registran en xuper-library (recent y recent_items), usando una clave estable de episodio o URL.
- Se refuerzan los estilos del contenedor externo para quitar contornos, bordes y sombras. Un borde dibujado dentro de un iframe cross-origin puede no ser modificable desde la app.

No se pudo ejecutar assembleRelease aquí; validar con GitHub Actions.
