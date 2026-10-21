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

---
# FrameXTV con anime (ruta propia, id de AniList)

FrameXTV tiene ruta de anime: `https://framextv.xyz/embed/anime?id=<anilist>&type=tv&season=<t>&episode=<e>&sub_type=sub`.
Ese `id` es de **AniList**, no de TMDb.

- `data/AniListResolver.kt` (nuevo): al abrir la ficha de una serie de anime, busca en la API pública de AniList
  (`graphql.anilist.co`) por título original + título + año de estreno y guarda el id en `anilist_ids.json`
  (caché en disco: una sola consulta por serie). Si no hay coincidencia clara (puntaje < 0,75), no inventa nada.
- `WebSourceRegistry.frameXtvUrl`: si la serie es anime y ya se resolvió su id de AniList, arma la ruta de anime;
  si no, usa el embed normal de TMDb como antes (no se rompe nada).
- `TmdbCatalogService.loadSeasons`: dispara la resolución (máx. 7 s) al abrir la ficha.

## Supuestos a verificar
- Se manda `season` y `episode` de TMDb con el id de AniList de la **primera** entrada de la serie. AniList separa
  muchas series en una entrada por temporada y TMDb no; si en temporadas 2+ no reproduce o abre otro episodio,
  pasame un link que funcione y cambio a "un id de AniList por temporada".
- `sub_type=sub` fijo (como en tu ejemplo). Si querés `dub`, es la constante `FRAMEXTV_ANIME_SUB_TYPE`.
- La API de AniList no se pudo probar desde el entorno de edición. Si un anime no reproduce, verificar que el id
  guardado sea el correcto (anilist.co/anime/<id>).
- Solo series. No compilado ni probado en el TV.
