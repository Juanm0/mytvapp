# XuperQuilmesTV — correcciones incrementales

Reemplazar únicamente estos dos archivos en sus rutas correspondientes:
- `app/src/main/java/com/quilmes/xuper/MainActivity.kt`
- `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

Cambios:
- Importa `setValue` para el estado delegado del mini-hub.
- Importa `width` para el modificador del mini-hub.
- Registra reproducciones embebidas en `xuper-library` / `recent_items` para que puedan aparecer en Continuar viendo.
- Configura el embed público de VideoAPI con `skin=netflix` y conserva los parámetros de autoplay, subtítulos y controles.

Limitaciones verificadas contra la documentación pública de VideoAPI: `start_at` permite iniciar desde un segundo conocido, pero la API pública documenta que `post_message/communication` está bloqueado. No hay callback público documentado para leer `currentTime`/`duration` desde la app. Por eso esta entrega no puede prometer progreso exacto, reanudar en el minuto guardado ni activar la cuenta regresiva del mini-hub de forma fiable en VideoAPI. Para eso VideoAPI tendría que exponer un callback/protocolo autorizado o un endpoint de progreso.

La selección de skin puede cambiar el marco visual, pero no garantiza eliminar un borde dibujado dentro del reproductor. Validar en la TV y ejecutar GitHub Actions antes de instalar.
