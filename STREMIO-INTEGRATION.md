# Integración de addons Stremio en Xuper

- En la ficha de una película o serie TMDB se resuelve el IMDb ID con `/movie/{tmdb_id}/external_ids` o `/tv/{tmdb_id}/external_ids`.
- Películas consultan `stream/movie/{imdb_id}.json`.
- Cada episodio de una serie consulta `stream/series/{imdb_id}:{season}:{episode}.json`.
- Se pueden configurar varias URLs de `manifest.json`, separadas por coma, desde la ficha de contenido.
- Solo se muestran enlaces directos HTTP/HTTPS que Media3 pueda intentar abrir. `infoHash`/torrents y enlaces externos no se envían al reproductor porque ExoPlayer no los reproduce directamente sin una capa adicional.
- El addon precargado es el addon oficial de películas de dominio público: `https://caching.stremio.net/publicdomainmovies.now.sh/manifest.json`. Su catálogo es limitado y no garantiza resultados para todo TMDB. Para más cobertura, agregá los manifests de addons que tengas autorización para usar y que ofrezcan enlaces directos compatibles.
- Un catálogo TMDB completo no significa que existan streams para todos los títulos; los addons determinan la disponibilidad real.

Referencias del protocolo: https://stremio.github.io/stremio-addon-sdk/protocol.html
