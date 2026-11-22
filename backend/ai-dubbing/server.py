"""Authenticated OyunTR relay for Chatterbox Multilingual V3."""
from __future__ import annotations

import hmac
import hashlib
import io
import json
import os
import re
import threading
import urllib.parse
import unicodedata
import uuid
import wave
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import torch
import torchaudio
from chatterbox.mtl_tts import ChatterboxMultilingualTTS

APP_TOKEN = os.environ.get("OYUNTR_APP_TOKEN", "").strip()
HOST = os.environ.get("OYUNTR_BIND", "127.0.0.1")
PORT = int(os.environ.get("OYUNTR_PORT", "8787"))
VOICE_DIR = Path(os.environ.get("CHATTERBOX_VOICES_DIR", "voice_references")).resolve()
CACHE_DIR = Path(os.environ.get("CHATTERBOX_CACHE_DIR", "speech_cache")).resolve()
CACHE_MAX_GB = max(0.25, float(os.environ.get("CHATTERBOX_CACHE_MAX_GB", "4")))
CACHE_MAX_BYTES = int(CACHE_MAX_GB * 1024 * 1024 * 1024)
MAX_TEXT = 4096
MAX_AUDIO = 16 * 1024 * 1024
VOICE_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
MODEL: ChatterboxMultilingualTTS | None = None
MODEL_LOCK = threading.Lock()
CACHE_LOCK = threading.Lock()


def load_device() -> str:
    if torch.cuda.is_available():
        return "cuda"
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def wav_duration_is_valid(data: bytes) -> bool:
    if len(data) < 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return False
    try:
        with wave.open(io.BytesIO(data), "rb") as source:
            return source.getnchannels() in (1, 2) and 0.5 <= source.getnframes() / source.getframerate() <= 30
    except (wave.Error, ZeroDivisionError):
        return False


def voice_metadata_path(voice_id: str) -> Path:
    return VOICE_DIR / f"{voice_id}.json"


def canonical_subtitle(text: str) -> str:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    normalized = "".join(" " if unicodedata.category(char).startswith("P") else char for char in normalized)
    return re.sub(r"\s+", " ", normalized).strip()


def speech_cache_key(text: str, game_id: str, reference: Path, exaggeration: float) -> str:
    reference_hash = hashlib.sha256(reference.read_bytes()).hexdigest()
    key_data = {
        "cache_schema": 1,
        "model": "chatterbox-multilingual-v3",
        "language": "tr",
        "game_id": game_id.strip().casefold()[:128],
        "reference_sha256": reference_hash,
        "exaggeration": round(exaggeration, 3),
        "text": canonical_subtitle(text),
    }
    serialized = json.dumps(key_data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def read_cached_audio(key: str) -> bytes | None:
    path = CACHE_DIR / f"{key}.wav"
    with CACHE_LOCK:
        try:
            audio = path.read_bytes()
            if len(audio) < 44 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE":
                path.unlink(missing_ok=True)
                return None
            os.utime(path, None)
            return audio
        except FileNotFoundError:
            return None


def write_cached_audio(key: str, audio: bytes) -> None:
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    target = CACHE_DIR / f"{key}.wav"
    temporary = CACHE_DIR / f".{key}.{uuid.uuid4().hex}.tmp"
    with CACHE_LOCK:
        temporary.write_bytes(audio)
        temporary.replace(target)
        entries = sorted(CACHE_DIR.glob("*.wav"), key=lambda path: path.stat().st_atime_ns)
        total_size = sum(path.stat().st_size for path in entries)
        for entry in entries:
            if total_size <= CACHE_MAX_BYTES:
                break
            size = entry.stat().st_size
            entry.unlink(missing_ok=True)
            total_size -= size


class Handler(BaseHTTPRequestHandler):
    server_version = "OyunTRChatterboxRelay/1.0"

    def _json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        return bool(APP_TOKEN) and hmac.compare_digest(
            self.headers.get("Authorization", ""), f"Bearer {APP_TOKEN}"
        )

    def do_GET(self) -> None:
        if self.path == "/health":
            return self._json(200, {"ok": MODEL is not None, "model": "chatterbox-multilingual-v3"})
        if self.path == "/v1/voices":
            if not self._authorized():
                return self._json(401, {"error": "unauthorized"})
            voices = []
            VOICE_DIR.mkdir(parents=True, exist_ok=True)
            for audio_path in sorted(VOICE_DIR.glob("*.wav")):
                voice_id = audio_path.stem
                if not VOICE_ID_RE.fullmatch(voice_id):
                    continue
                try:
                    metadata = json.loads(voice_metadata_path(voice_id).read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    metadata = {}
                voices.append({
                    "id": voice_id,
                    "name": metadata.get("name") or voice_id,
                    "category": "Klonlanmış ses",
                })
            return self._json(200, {"model": "chatterbox-multilingual-v3", "voices": voices})
        return self._json(404, {"error": "not found"})

    def do_POST(self) -> None:
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path.startswith("/v1/voices/") and parsed.path.endswith("/reference"):
            return self._upload_voice(parsed)
        if parsed.path != "/v1/speech":
            return self._json(404, {"error": "not found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 24 * 1024:
                return self._json(413, {"error": "request size is invalid"})
            payload = json.loads(self.rfile.read(size))
            text = str(payload.get("text", "")).strip()
            voice_id = str(payload.get("voice_id", "")).strip()
            game_id = str(payload.get("game_id", "unknown")).strip() or "unknown"
            try:
                exaggeration = float(payload.get("exaggeration", 0.5))
            except (TypeError, ValueError):
                return self._json(400, {"error": "exaggeration must be numeric"})
            if not text or len(text) > MAX_TEXT or not VOICE_ID_RE.fullmatch(voice_id):
                return self._json(400, {"error": "text or voice_id is invalid"})
            reference = VOICE_DIR / f"{voice_id}.wav"
            if not reference.is_file():
                return self._json(404, {"error": "upload a voice reference for this character first"})
            if MODEL is None:
                return self._json(503, {"error": "Chatterbox model is not loaded"})
            exaggeration = max(0.25, min(1.0, exaggeration))
            cache_key = speech_cache_key(text, game_id, reference, exaggeration)
            output = read_cached_audio(cache_key)
            cache_status = "HIT" if output is not None else "MISS"
            if output is None:
                # Avoid duplicate inference when concurrent clients request the same line.
                with MODEL_LOCK:
                    output = read_cached_audio(cache_key)
                    if output is not None:
                        cache_status = "HIT"
                    else:
                        audio = MODEL.generate(
                            text,
                            language_id="tr",
                            audio_prompt_path=str(reference),
                            exaggeration=exaggeration,
                        )
                        wav = io.BytesIO()
                        torchaudio.save(wav, audio.detach().cpu(), MODEL.sr, format="wav")
                        output = wav.getvalue()
                        if not output or len(output) > MAX_AUDIO:
                            return self._json(502, {"error": "Chatterbox returned invalid audio size"})
                        write_cached_audio(cache_key, output)
            if len(output) > MAX_AUDIO:
                return self._json(502, {"error": "Chatterbox returned invalid audio size"})
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(output)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-OyunTR-TTS-Cache", cache_status)
            self.end_headers()
            self.wfile.write(output)
        except (ValueError, json.JSONDecodeError):
            self._json(400, {"error": "invalid JSON request"})
        except (BrokenPipeError, ConnectionResetError):
            return
        except Exception as error:
            self._json(502, {"error": f"Chatterbox failed ({type(error).__name__})"})

    def do_DELETE(self) -> None:
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        parsed = urllib.parse.urlsplit(self.path)
        parts = parsed.path.strip("/").split("/")
        voice_id = parts[2] if len(parts) == 4 and parts[:2] == ["v1", "voices"] and parts[3] == "reference" else ""
        if not VOICE_ID_RE.fullmatch(voice_id):
            return self._json(400, {"error": "voice_id is invalid"})
        audio_path = VOICE_DIR / f"{voice_id}.wav"
        metadata_path = voice_metadata_path(voice_id)
        if not audio_path.is_file():
            return self._json(404, {"error": "voice reference not found"})
        try:
            with MODEL_LOCK:
                audio_path.unlink()
                metadata_path.unlink(missing_ok=True)
        except OSError:
            return self._json(500, {"error": "voice reference could not be removed"})
        self.send_response(204)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def _upload_voice(self, parsed: urllib.parse.SplitResult) -> None:
        parts = parsed.path.strip("/").split("/")
        voice_id = parts[2] if len(parts) == 4 else ""
        if not VOICE_ID_RE.fullmatch(voice_id):
            return self._json(400, {"error": "voice_id is invalid"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > MAX_AUDIO:
                return self._json(413, {"error": "voice sample must be under 16 MB"})
            sample = self.rfile.read(size)
            if not wav_duration_is_valid(sample):
                return self._json(400, {"error": "provide a mono/stereo WAV sample between 0.5 and 30 seconds"})
            name = urllib.parse.parse_qs(parsed.query).get("name", [voice_id])[0].strip()[:80] or voice_id
            VOICE_DIR.mkdir(parents=True, exist_ok=True)
            target = VOICE_DIR / f"{voice_id}.wav"
            temp = VOICE_DIR / f".{voice_id}.upload"
            temp.write_bytes(sample)
            temp.replace(target)
            voice_metadata_path(voice_id).write_text(
                json.dumps({"name": name}, ensure_ascii=False), encoding="utf-8"
            )
            return self._json(201, {"id": voice_id, "name": name, "category": "Klonlanmış ses"})
        except (ValueError, OSError):
            return self._json(400, {"error": "voice sample could not be saved"})

    def log_message(self, fmt: str, *args) -> None:
        # Keep subtitles, voice references, and tokens out of logs.
        super().log_message("request completed")


def main() -> None:
    global MODEL
    if len(APP_TOKEN) < 32:
        raise SystemExit("Set an OYUNTR_APP_TOKEN of at least 32 characters")
    VOICE_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    device = load_device()
    print(f"Loading Chatterbox Multilingual V3 on {device}; the first run downloads model weights...")
    MODEL = ChatterboxMultilingualTTS.from_pretrained(device=device, t3_model="v3")
    print(f"OyunTR Chatterbox relay listening on {HOST}:{PORT}; references: {VOICE_DIR}; cache: {CACHE_DIR}")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
