# FrameXTV como reproductor predeterminado (prueba)

- Las fichas del catálogo TMDB (`tmdb-movie-ID` y `tmdb-tv-ID`) muestran **Reproducir** aunque todavía no tengan una fuente IPTV asociada.
- Al pulsar **Reproducir**, se abre el embed `https://framextv.xyz/embed/ID?autoplay=1&muted=0` en el reproductor WebView de la app.
- Las películas usan el ID numérico de TMDB. Para series se aplica deliberadamente la misma fórmula en esta primera prueba; la ruta correcta de episodios/series queda pendiente de validar.
- Las fuentes IPTV y la reproducción de canales en vivo conservan su flujo anterior.

Esta integración no confirma que FrameXTV tenga disponible cada título. Si el sitio no encuentra el ID o usa una ruta distinta para series, el embed puede fallar y se corrige en un siguiente fix.
