#!/usr/bin/env python3
"""Host-only fake-native queue/status tests; no Android device, microphone or model."""
import glob
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parent
source = root / "app/src/androidTest/java/de/kontrakurs/voskfixture"
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
def find_one(pattern):
    matches = glob.glob(str(cache / pattern), recursive=True)
    if len(matches) != 1:
        raise SystemExit(f"expected one cached dependency: {pattern}")
    return matches[0]
junit = find_one("junit/junit/4.13.2/**/*.jar")
hamcrest = find_one("org.hamcrest/hamcrest-core/1.3/**/*.jar")
with tempfile.TemporaryDirectory(prefix="kp-vosk-java-") as directory:
    cp = os.pathsep.join([junit, hamcrest])
    subprocess.run(["javac", "-J-Xmx96m", "--release", "17", "-cp", cp, "-d", directory,
                    str(source / "BoundedPcmDecoder.java"), str(source / "BoundedPcmDecoderTest.java")], check=True, timeout=30)
    subprocess.run(["java", "-Xmx64m", "-cp", os.pathsep.join([directory, cp]), "org.junit.runner.JUnitCore",
                    "de.kontrakurs.voskfixture.BoundedPcmDecoderTest"], check=True, timeout=20)
