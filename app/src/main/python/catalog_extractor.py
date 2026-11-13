import json
from urllib.parse import urlparse
from yt_dlp import YoutubeDL

ALLOWED_PAGE_HOSTS = {"videoapi.la", "videoapp.zip"}


def resolve(page_url):
    parsed = urlparse(str(page_url or "").strip())
    host = (parsed.hostname or "").lower().rstrip(".")
    if parsed.scheme != "https" or parsed.username or parsed.password or not any(
        host == allowed or host.endswith("." + allowed) for allowed in ALLOWED_PAGE_HOSTS
    ):
        raise ValueError("La página de origen no pertenece a un proveedor de Xuper permitido.")

    options = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
        "format": "best[protocol^=m3u8]/best[ext=mp4]/best",
        "socket_timeout": 8,
        "retries": 0,
        "extractor_retries": 0,
        "fragment_retries": 0,
        "ignoreerrors": False,
    }

    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(page_url, download=False)

    candidates = []
    if info.get("url"):
        candidates.append(info)
    candidates.extend(info.get("requested_formats") or [])
    candidates.extend(info.get("formats") or [])
    candidates = [item for item in candidates if item.get("url")]
    if not candidates:
        raise RuntimeError("yt-dlp no encontró una URL de video directa en el proveedor.")

    def score(item):
        protocol = str(item.get("protocol") or "").lower()
        ext = str(item.get("ext") or "").lower()
        return (0 if "m3u8" in protocol else 1 if ext == "mp4" else 2,
                -(int(item.get("height") or 0)))

    selected = sorted(candidates, key=score)[0]
    stream_url = str(selected.get("url") or "")
    stream = urlparse(stream_url)
    if stream.scheme != "https" or not stream.hostname or stream.username or stream.password:
        raise RuntimeError("yt-dlp devolvió una URL de video no segura.")

    protocol = str(selected.get("protocol") or selected.get("ext") or "").lower()
    path = stream.path.lower()
    stream_type = "hls" if "m3u8" in protocol or path.endswith(".m3u8") else "mp4"
    raw_headers = selected.get("http_headers") or info.get("http_headers") or {}
    headers = {str(k): str(v) for k, v in raw_headers.items() if v is not None}

    subtitle_tracks = []
    for language, tracks in (info.get("subtitles") or {}).items():
        for track in tracks or []:
            subtitle_url = str(track.get("url") or "")
            subtitle_parsed = urlparse(subtitle_url)
            if subtitle_parsed.scheme != "https" or not subtitle_parsed.hostname:
                continue
            ext = str(track.get("ext") or "").lower()
            if ext not in {"vtt", "srt", "ass", "ssa", "ttml"}:
                continue
            subtitle_tracks.append({
                "url": subtitle_url,
                "language": str(language or "und"),
                "label": str(track.get("name") or language or "Subtítulo"),
                "ext": ext,
            })

    return json.dumps({
        "streamUrl": stream_url,
        "streamType": stream_type,
        "quality": (str(selected.get("height")) + "p") if selected.get("height") else "Auto",
        "headers": headers,
        "subtitles": subtitle_tracks,
    }, ensure_ascii=False)
