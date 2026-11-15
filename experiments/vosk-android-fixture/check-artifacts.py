#!/usr/bin/env python3
"""Build-only checks. This never opens a microphone or runs an Android device."""
import glob
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parent
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
artifacts = {
    "com.alphacephei/vosk-android/0.3.75/*/vosk-android-0.3.75.aar": "ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad",
    "net.java.dev.jna/jna/5.18.1/*/jna-5.18.1.aar": "7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0",
}
for pattern, expected in artifacts.items():
    matches = list(cache.glob(pattern))
    if len(matches) != 1:
        raise SystemExit(f"expected one pinned AAR: {pattern}, found {len(matches)}")
    with matches[0].open("rb") as f:
        actual = hashlib.file_digest(f, "sha256").hexdigest()
    if actual != expected:
        raise SystemExit(f"unexpected AAR checksum: {pattern}")
    print(f"verified AAR {pattern}: {actual}")

apks = [root / "app/build/outputs/apk/debug/app-debug.apk",
        root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
aapts = sorted(glob.glob(str(sdk / "build-tools/*/aapt")))
if not aapts:
    raise SystemExit("Android aapt not found")
for apk in apks:
    if not apk.is_file():
        raise SystemExit(f"APK missing: {apk}")
    permissions = subprocess.check_output([aapts[-1], "dump", "permissions", str(apk)], text=True)
    if any(p in permissions for p in ("android.permission.RECORD_AUDIO", "android.permission.INTERNET")):
        raise SystemExit(f"capture or network permission in {apk.name}: {permissions}")
    with apk.open("rb") as f:
        print(f"{apk.name}: bytes={apk.stat().st_size}, sha256={hashlib.file_digest(f, 'sha256').hexdigest()}")
    print(f"{apk.name}: microphone and Internet permissions absent")

with zipfile.ZipFile(apks[0]) as apk:
    names = set(apk.namelist())
    for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
        for lib in ("libvosk.so", "libjnidispatch.so"):
            name = f"lib/{abi}/{lib}"
            if name not in names:
                raise SystemExit(f"missing Android native library {name}")
            print(f"present: {name}")
print("Build-only check; model and instrumentation/device tests were NOT run.")
