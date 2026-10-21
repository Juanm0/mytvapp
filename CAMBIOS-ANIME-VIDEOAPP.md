# Latino con anime: dominio y ruta propios (21/10/2026)

VideoAPI sirve el anime desde `https://videoapp.zip/e/anime/<id>/<temporada>/<episodio>` (las series normales
siguen en `https://videoapi.la/e/tv/<id>/<temporada>/<episodio>` y las películas en `/e/movie/<id>`).

- `WebSourceRegistry.kt`: arma la URL de anime si la serie está marcada como anime; centraliza los hosts de
  VideoAPI (`isVideoApiHost`, `isVideoApiEmbedUrl`).
- `TmdbCatalogService.kt`: marca como anime a toda serie de TMDb con género Animación (16) + idioma original
  japonés. Se marca al ver filas, búsquedas, relacionados y al abrir la ficha (`tv/{id}`).
- `PlayerScreen.kt` y `MainActivity.kt`: dejaron de tener `videoapi.la` fijo, así que el reproductor Latino
  (hub, agente de frames, subtítulos en español, siguiente episodio) también funciona con `videoapp.zip`.

## Supuestos a verificar
- Que el `<id>` de `/e/anime/` sea el **id de TMDb** de la serie. Comprobación: en themoviedb.org/tv/<id>
  tiene que abrir el mismo anime del link que funciona.
- Solo series (con temporada/episodio). Las películas de anime siguen yendo a `/e/movie/`.
- La marca de anime vive en memoria: hay que abrir la ficha antes de tocar Latino (siempre pasa así).
- Los parámetros (`autoplay`, `auto_next`, etc.) se mandan igual que en videoapi.la; si el dominio nuevo no
  los soporta, simplemente los ignora.
- No compilado ni probado en el TV.
