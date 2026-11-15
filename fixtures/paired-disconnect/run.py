#!/usr/bin/env python3
"""Compile the real PairedCapture against synthetic LAN/recorder objects; no device."""
import argparse
import glob
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--json-jar", required=True, type=Path, help="org.json JVM jar")
args = parser.parse_args()
root = Path.home() / ".gradle/caches/modules-2/files-2.1"
def dependency(pattern):
    found = glob.glob(str(root / pattern))
    if len(found) != 1:
        raise SystemExit(f"Expected one cached dependency for {pattern}; got {len(found)}")
    return found[0]

compiler = dependency("org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/*/*.jar")
stdlib = dependency("org.jetbrains.kotlin/kotlin-stdlib/2.2.20/*/*.jar")
script = dependency("org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/*/*.jar")
reflect = dependency("org.jetbrains.kotlin/kotlin-reflect/2.2.0/*/*.jar")
coroutines = dependency("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar")
annotations = dependency("org.jetbrains/annotations/13.0/*/*.jar")
directory = Path(__file__).resolve().parent
repo = directory.parents[1]
video = repo / "app/src/main/java/de/kontrakurs/kontraprompter/video"
sources = [video / "PairedCapture.kt", video / "PairedCapturePolicy.kt", *(directory / name for name in ("TransportStub.kt", "RecorderStub.kt", "Probe.kt"))]
runtime = [stdlib, coroutines, str(args.json_jar.resolve()), annotations]
with tempfile.TemporaryDirectory(prefix="kp-paired-disconnect-") as output:
    subprocess.run(["java", "-Xmx256m", "-cp", os.pathsep.join([compiler, stdlib, script, reflect, coroutines, annotations]),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(runtime),
                    "-d", output, *map(str, sources)], check=True)
    subprocess.run(["java", "-Xmx128m", "-cp", os.pathsep.join([output, *runtime]),
                    "de.kontrakurs.kontraprompter.video.ProbeKt"], check=True)
