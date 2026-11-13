import json
import re
from urllib.parse import quote
from yt_dlp import YoutubeDL


def resolve(viewkey):
    key = str(viewkey or "").strip()
    if not re.fullmatch(r"[A-Za-z0-9_-]{5,80}", key):
        raise ValueError("Identificador de video inválido.")

    page_url = "https://www.pornhub.com/view_video.php?viewkey=" + quote(key)
    options = {
        "quiet": True,
        "no_warnings": True,
        "no_playlist": True,
        "skip_download": True,
        "format": "best[protocol^=m3u8]/best",
        "socket_timeout": 25,
        "retries": 2,
        "extractor_retries": 2,
        "fragment_retries": 2,
        "noplaylist": True,
    }

    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(page_url, download=False)

    selected = info
    if not info.get("url"):
        formats = info.get("requested_formats") or info.get("formats") or []
        candidates = [f for f in formats if f.get("url")]
        if not candidates:
            raise RuntimeError("yt-dlp no encontró un formato reproducible.")
        hls = [f for f in candidates if "m3u8" in str(f.get("protocol", "")).lower()
               or str(f.get("ext", "")).lower() == "m3u8"]
        selected = (hls or candidates)[0]

    stream_url = str(selected.get("url") or "")
    from urllib.parse import urlparse
    parsed = urlparse(stream_url)
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or not (host == "phncdn.com" or host.endswith(".phncdn.com")):
        raise RuntimeError("El extractor devolvió un host de video no permitido.")

    headers = selected.get("http_headers") or info.get("http_headers") or {}
    protocol = str(selected.get("protocol") or selected.get("ext") or "").lower()
    stream_type = "hls" if "m3u8" in protocol or ".m3u8" in parsed.path.lower() else "mp4"
    result = {
        "title": str(info.get("title") or "Video +18")[:240],
        "streamUrl": stream_url,
        "streamType": stream_type,
        "quality": (str(selected.get("height")) + "p") if selected.get("height") else str(selected.get("format_note") or selected.get("format_id") or "Auto"),
        "headers": headers,
    }
    return json.dumps(result, ensure_ascii=False)
