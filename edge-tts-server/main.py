import asyncio
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import edge_tts
from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
from pydantic import BaseModel, Field

app = FastAPI(title="SIGYI Edge TTS Service", version="1.0.0")
VOICE = os.getenv("EDGE_TTS_VOICE", "my-MM-NilarNeural")

class TTSRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    rate: int = Field(default=100, ge=70, le=130)
    pitch: int = Field(default=0, ge=-20, le=20)

@app.get("/health")
def health():
    return {"ok": True, "voice": VOICE}

@app.post("/tts")
async def tts(req: TTSRequest):
    if not shutil.which("ffmpeg"):
        raise HTTPException(500, "ffmpeg binary is not installed")
    with tempfile.TemporaryDirectory(prefix="sigyi-tts-") as temp:
        mp3 = Path(temp) / "speech.mp3"
        wav = Path(temp) / "speech.wav"
        try:
            rate = f"{req.rate - 100:+d}%"
            pitch = f"{req.pitch:+d}Hz"
            communicate = edge_tts.Communicate(req.text, VOICE, rate=rate, pitch=pitch)
            await communicate.save(str(mp3))
            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(mp3),
                "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le", str(wav),
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            _, stderr = await proc.communicate()
            if proc.returncode != 0 or not wav.exists() or wav.stat().st_size <= 44:
                raise RuntimeError(stderr.decode("utf-8", "replace")[-1000:])
            return Response(wav.read_bytes(), media_type="audio/wav", headers={"Cache-Control":"no-store"})
        except Exception as exc:
            raise HTTPException(502, f"Edge TTS failed: {str(exc)[:300]}")
