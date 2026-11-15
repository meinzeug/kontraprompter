#!/usr/bin/env python3
"""Verify synthetic PCM source or the exact PCM bytes in a test APK."""
import hashlib
from pathlib import Path
import sys
import zipfile

expected_size = 556_732
expected_sha = "cfcfe20034a34e79326187276cd8d5225284655239d82e24f65b6d0b5d9cc311"

def check(source):
    data = source.read(expected_size + 1)
    if len(data) != expected_size or hashlib.sha256(data).hexdigest() != expected_sha:
        raise SystemExit("synthetic PCM size or SHA-256 mismatch")
    print(f"verified synthetic PCM: bytes={len(data)} sha256={expected_sha}")

if len(sys.argv) == 2:
    with Path(sys.argv[1]).open("rb") as source:
        check(source)
elif len(sys.argv) == 3 and sys.argv[1] == "--apk":
    with zipfile.ZipFile(sys.argv[2]) as apk:
        entry = "assets/synthetic-de-48khz-s16le.pcm"
        if apk.namelist().count(entry) != 1:
            raise SystemExit("test APK must contain exactly one synthetic PCM asset")
        with apk.open(entry) as source:
            check(source)
else:
    raise SystemExit("usage: check-synthetic-asset.py FILE | --apk TEST_APK")
