# Mini-hub "Episodio siguiente" también en Latino (20/10/2026)

Reemplazar solo: `app/src/main/java/com/quilmes/xuper/ui/screens/PlayerScreen.kt`

- La cuenta regresiva de 30 s ya no es exclusiva de FrameXTV: Latino (VideoAPI), OK.ru y Simuladores la usan con el tiempo/duración que informa el agente de frames (o el listener `timeupdate`).
- Detección de final por tiempo (queda ≤ 1 s) también para esos players, además del evento `ended`.
- Guarda anti-publicidad: fuera de FrameXTV solo cuenta si la duración del video es ≥ 120 s (`MIN_WEB_EPISODE_SECONDS`).
- No se tocó nada más (hub, seek, subtítulos, FrameXTV).

No compilado ni probado en el TV (sin Android SDK en el entorno). Probar con un episodio de Latino que tenga siguiente: tiene que aparecer a los 30 s del final.
