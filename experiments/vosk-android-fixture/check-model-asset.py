#!/usr/bin/env python3
"""Verify the official model ZIP before build or the exact ZIP embedded in a test APK."""
import hashlib
from pathlib import Path
import sys
import zipfile

expected_size = 46_499_967
expected_sha = "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43"

def check(stream):
    digest = hashlib.sha256()
    size = 0
    while True:
        part = stream.read(1024 * 1024)
        if not part:
            break
        size += len(part)
        if size > expected_size:
            raise SystemExit("model exceeds expected size")
        digest.update(part)
    if size != expected_size or digest.hexdigest() != expected_sha:
        raise SystemExit("model size or SHA-256 mismatch")
    print(f"verified model ZIP: bytes={size} sha256={digest.hexdigest()}")

if len(sys.argv) == 2:
    with Path(sys.argv[1]).open("rb") as source:
        check(source)
elif len(sys.argv) == 3 and sys.argv[1] == "--apk":
    with zipfile.ZipFile(sys.argv[2]) as apk:
        entry = "assets/vosk-model-small-de-0.15.zip"
        if apk.namelist().count(entry) != 1:
            raise SystemExit("test APK must contain exactly one model ZIP")
        with apk.open(entry) as source:
            check(source)
else:
    raise SystemExit("usage: check-model-asset.py FILE | --apk TEST_APK")
