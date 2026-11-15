#!/usr/bin/env python3
"""Verify test-only copies of the exact reviewed source revisions."""
import hashlib
from pathlib import Path

source = Path(__file__).resolve().parent / "app/src/androidTest/java/de/kontrakurs/kontraprompter"
expected = {
    "video/PcmTee.kt": "63a00a6b1e2db01aa3f9510cd55335051f4d402bac0775231ac309e39dfa993d",
    "video/SpeechLocalDeliveryFence.kt": "4abea76cf5cf799dad22df69aa64c2986d90745210e1f2c8725421d912cbc04c",
    "speech/SpokenScript.kt": "08bdd5861f40fc7dc865bfaa8fb75f697e08120c743a830faad10bebae1d24f1",
}
for name, digest in expected.items():
    actual = hashlib.sha256((source / name).read_bytes()).hexdigest()
    if actual != digest:
        raise SystemExit("unexpected test-only pipeline source: " + name)
    print("verified test-only source:", name, actual)
