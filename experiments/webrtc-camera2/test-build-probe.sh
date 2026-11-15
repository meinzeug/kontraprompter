#!/usr/bin/env bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1
sdk=${ANDROID_HOME:-"$HOME/Android/Sdk"}
shopt -s globstar nullglob
find_one() {
  local paths=( $1 )
  if ((${#paths[@]} != 1)); then printf 'Expected one cached dependency: %s\n' "$1" >&2; return 1; fi
  printf '%s' "${paths[0]}"
}
aar=$(find_one "$cache/io.github.webrtc-sdk/android/150.7871.04/**/*.aar")
annotation=$(find_one "$cache/androidx.annotation/annotation-jvm/1.9.1/**/*.jar")
junit=$(find_one "$cache/junit/junit/4.13.2/**/*.jar")
hamcrest=$(find_one "$cache/org.hamcrest/hamcrest-core/1.3/**/*.jar")
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/kontraprompter-camera2-probe.XXXXXXXX")
trap 'rm -rf -- "$test_dir"' EXIT
mkdir "$test_dir/classes" "$test_dir/tests" "$test_dir/dex"
python3 - "$aar" "$test_dir/webrtc.jar" <<'PY'
import hashlib,sys,zipfile
with open(sys.argv[1], 'rb') as f:
    if hashlib.file_digest(f, 'sha256').hexdigest() != '1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c':
        raise SystemExit('unexpected WebRTC AAR')
with zipfile.ZipFile(sys.argv[1]) as aar:
    with open(sys.argv[2], 'wb') as f: f.write(aar.read('classes.jar'))
PY
python3 "$here/generate_probe.py" "$test_dir/generated"
app_sources="$here/../../app/src/main/java/org/webrtc"
cmp "$test_dir/generated/org/webrtc/KpExperimentalCamera2Session.java" "$app_sources/KpExperimentalCamera2Session.java"
cmp "$test_dir/generated/org/webrtc/KpExperimentalCamera2Capturer.java" "$app_sources/KpExperimentalCamera2Capturer.java"
cmp "$here/KpFixedFpsSelector.java" "$app_sources/KpFixedFpsSelector.java"
sdkjar="$sdk/platforms/android-36/android.jar"
javac -J-Xmx256m --release 17 -cp "$test_dir/webrtc.jar:$sdkjar:$annotation" -d "$test_dir/classes" \
  "$test_dir/generated/org/webrtc/KpExperimentalCamera2Session.java" \
  "$test_dir/generated/org/webrtc/KpExperimentalCamera2Capturer.java" "$here/KpFixedFpsSelector.java"
javap -c -p -cp "$test_dir/classes:$test_dir/webrtc.jar" org.webrtc.KpExperimentalCamera2Session > "$test_dir/session-bytecode.txt"
rg -Fq 'KpFixedFpsSelector.select' "$test_dir/session-bytecode.txt"
javac -J-Xmx128m --release 17 -cp "$test_dir/classes:$test_dir/webrtc.jar:$junit:$hamcrest" -d "$test_dir/tests" "$here/KpFixedFpsSelectorTest.java"
java -Xmx96m -cp "$test_dir/tests:$test_dir/classes:$test_dir/webrtc.jar:$junit:$hamcrest" org.junit.runner.JUnitCore org.webrtc.KpFixedFpsSelectorTest
python3 - "$test_dir/webrtc.jar" "$test_dir/classes" <<'PY'
import pathlib,sys,zipfile
root=pathlib.Path(sys.argv[2])
custom={p.relative_to(root).as_posix() for p in root.rglob('*.class')}
with zipfile.ZipFile(sys.argv[1]) as jar: collisions=custom.intersection(jar.namelist())
if collisions: raise SystemExit('duplicate classes: '+str(sorted(collisions)))
print('No duplicate class paths with pinned WebRTC AAR:', len(custom), 'experimental classes')
PY
jar cf "$test_dir/experimental.jar" -C "$test_dir/classes" .
"$sdk/build-tools/35.0.0/d8" -JXmx512M --min-api 26 --lib "$sdkjar" --classpath "$annotation" --output "$test_dir/dex" "$test_dir/webrtc.jar" "$test_dir/experimental.jar"
test -s "$test_dir/dex/classes.dex"
printf 'D8 combined WebRTC + experimental classes: OK\n'
