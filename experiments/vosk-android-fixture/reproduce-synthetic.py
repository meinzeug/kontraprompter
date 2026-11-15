#!/usr/bin/env python3
"""Reproduce and compare the checked-in synthetic speech; no microphone or network."""
import hashlib
from pathlib import Path
import subprocess

text = "Hallo zusammen heute sprechen wir über Medien und Videos."
wav = subprocess.check_output(["espeak-ng", "-v", "de", "-s", "140", "--stdout", text])
pcm = subprocess.check_output(["ffmpeg", "-v", "error", "-nostdin", "-i", "pipe:0", "-ac", "1", "-ar", "48000", "-f", "s16le", "pipe:1"], input=wav)
generated = bytes(48_000) + pcm + bytes(96_000)  # 0.5 s initial / 1 s final silence
expected = Path(__file__).resolve().parent / "app/src/androidTest/assets/synthetic-de-48khz-s16le.pcm"
digest = hashlib.sha256(generated).hexdigest()
if generated != expected.read_bytes():
    raise SystemExit(f"synthetic fixture differs; generated SHA-256 {digest}; check eSpeak/ffmpeg versions")
print(f"synthetic fixture matches: bytes={len(generated)} sha256={digest}")
