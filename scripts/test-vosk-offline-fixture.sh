#!/usr/bin/env bash
set -euo pipefail
# Only synthetic eSpeak data and an already downloaded local model; no microphone or network.
root=$(cd "$(dirname "$0")/.." && pwd)
fixture="$root/.gradle/speech-fixture"
cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1
modelzip="$fixture/vosk-model-small-de-0.15.zip"
wheel="$fixture/wheels/vosk-0.3.45-py3-none-manylinux_2_12_x86_64.manylinux2010_x86_64.whl"
printf '%s  %s\n' b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43 "$modelzip" | sha256sum -c --quiet
printf '%s  %s\n' 25e025093c4399d7278f543568ed8cc5460ac3a4bf48c23673ace1e25d26619f "$wheel" | sha256sum -c --quiet
# Validate paths, declared total size, and entry types before extracting into our private cache.
python3 -B - "$modelzip" "$wheel" "$fixture" <<'PY'
import os, pathlib, shutil, stat, sys, tempfile, zipfile
archive,wheel,root=map(pathlib.Path,sys.argv[1:]); model=root/'vosk-model-small-de-0.15'
marker=model/'.kontra-fixture-model-sha256'
expected='b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43'
if model.exists():
    if model.is_symlink() or not marker.is_file() or marker.read_text().strip()!=expected:
        raise RuntimeError('Existing model is not our marked fixture')
else:
    temporary=pathlib.Path(tempfile.mkdtemp(prefix='model-extract-',dir=root))
    try:
        with zipfile.ZipFile(archive) as z:
            if sum(x.file_size for x in z.infolist())>150_000_000: raise ValueError('Model too large')
            for item in z.infolist():
                p=pathlib.PurePosixPath(item.filename)
                if p.is_absolute() or '..' in p.parts or not p.parts or p.parts[0]!='vosk-model-small-de-0.15': raise ValueError('Unsafe model path')
                kind=(item.external_attr >> 16) & 0o170000
                if kind not in (0,stat.S_IFREG,stat.S_IFDIR): raise ValueError('Unsafe model entry')
            z.extractall(temporary)
        made=temporary/'vosk-model-small-de-0.15'
        (made/'.kontra-fixture-model-sha256').write_text(expected+'\n')
        os.rename(made,model)
    finally: shutil.rmtree(temporary)
library=root/'libvosk-0.3.45.so'
with zipfile.ZipFile(wheel) as z:
    info=z.getinfo('vosk/libvosk.so')
    if info.file_size>50_000_000: raise ValueError('Library too large')
    data=z.read(info)
if library.exists():
    if library.is_symlink() or library.read_bytes()!=data: raise RuntimeError('Existing fixture library differs')
else:
    with library.open('xb') as out: out.write(data)
PY
shopt -s globstar nullglob
find_one() { local paths=( $1 ); if ((${#paths[@]} != 1)); then printf 'Dependency missing: %s\n' "$1" >&2; return 1; fi; printf '%s' "${paths[0]}"; }
compiler=$(find_one "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/**/*.jar")
stdlib=$(find_one "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/**/*.jar")
script=$(find_one "$cache/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/**/*.jar")
reflect=$(find_one "$cache/org.jetbrains.kotlin/kotlin-reflect/1.6.10/**/*.jar")
daemon=$(find_one "$cache/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20/**/*.jar")
coroutines=$(find_one "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/**/*.jar")
annotations=$(find_one "$cache/org.jetbrains/annotations/13.0/**/*.jar")
temporary=$(mktemp -d "${TMPDIR:-/tmp}/kp-offline-voice.XXXXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
espeak-ng -v de -s 140 -w "$temporary/synthetic.wav" 'Hallo zusammen heute sprechen wir über Medien und Videos.'
ffmpeg -v error -nostdin -i "$temporary/synthetic.wav" -ac 1 -ar 48000 -f s16le "$temporary/input.pcm"
src="$root/app/src/main/java/de/kontrakurs/kontraprompter"
probe="$root/app/src/test/java/de/kontrakurs/kontraprompter/speech/OfflinePcmProbe.kt"
java -XX:ActiveProcessorCount=1 -Xmx160m -cp "$compiler:$stdlib:$script:$reflect:$daemon:$coroutines:$annotations" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib:$annotations" \
  -d "$temporary/classes" "$src/video/PcmTee.kt" "$src/speech/SpokenScript.kt" "$probe"
export VOSK_FIXTURE_LIBRARY="$fixture/libvosk-0.3.45.so" VOSK_FIXTURE_MODEL="$fixture/vosk-model-small-de-0.15"
java -Xmx64m -cp "$temporary/classes:$stdlib" de.kontrakurs.kontraprompter.speech.OfflinePcmProbe pcm < "$temporary/input.pcm" |
  python3 -B "$root/scripts/vosk-offline-pcm-probe.py" > "$temporary/result.json"
transcript=$(python3 -B - "$temporary/input.pcm" "$temporary/result.json" <<'PY'
import hashlib,json,pathlib,sys
pcm=pathlib.Path(sys.argv[1]).read_bytes(); result=json.loads(pathlib.Path(sys.argv[2]).read_text())
if result['pcmBytes']!=len(pcm) or result['pcmSha256']!=hashlib.sha256(pcm).hexdigest(): raise ValueError('PCM changed across tee')
print(result['text'])
PY
)
printf 'synthetic transcript: %s\n' "$transcript"
result=$(java -Xmx64m -cp "$temporary/classes:$stdlib" de.kontrakurs.kontraprompter.speech.OfflinePcmProbe match "$transcript")
printf '%s\n' "$result"
[[ "$result" =~ ^matched_word_position=[1-9][0-9]*$ ]]
