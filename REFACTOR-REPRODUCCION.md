# Arquitectura de reproducción

- Las películas y series del catálogo TMDb usan VideoAPI como único reproductor web integrado.
- Películas: `https://videoapi.la/e/movie/{TMDB_ID}`.
- Episodios: `https://videoapi.la/e/tv/{TMDB_ID}/{temporada}/{episodio}`.
- La integración activa las opciones públicas documentadas `autoplay`, `auto_next`, `skip_intro`, `thumbnails`, `subtitles` y `quality_selector`.
- Los controles de reproducción pertenecen al player oficial de VideoAPI; Xuper no dibuja botones propios ni intercepta el D-pad en esta vista.
- El reproductor nativo de Android continúa reservado para fuentes IPTV/directas, no para los embeds de VideoAPI.
