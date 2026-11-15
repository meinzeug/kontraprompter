#!/usr/bin/env bash
set -euo pipefail
# Isolated JVM test: no Android device, microphone, recorder or network.
root=$(cd "$(dirname "$0")/.." && pwd)
cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1
shopt -s globstar nullglob
find_one() { local paths=( $1 ); if ((${#paths[@]} != 1)); then printf 'Missing dependency: %s\n' "$1" >&2; return 1; fi; printf '%s' "${paths[0]}"; }
compiler=$(find_one "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/**/*.jar")
stdlib=$(find_one "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/**/*.jar")
script=$(find_one "$cache/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/**/*.jar")
reflect=$(find_one "$cache/org.jetbrains.kotlin/kotlin-reflect/1.6.10/**/*.jar")
daemon=$(find_one "$cache/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20/**/*.jar")
coroutines=$(find_one "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/**/*.jar")
annotations=$(find_one "$cache/org.jetbrains/annotations/13.0/**/*.jar")
junit=$(find_one "$cache/junit/junit/4.13.2/**/*.jar")
hamcrest=$(find_one "$cache/org.hamcrest/hamcrest-core/1.3/**/*.jar")
temporary=$(mktemp -d "${TMPDIR:-/tmp}/kp-pcm-tap.XXXXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
src="$root/app/src/main/java/de/kontrakurs/kontraprompter/video/PcmTee.kt"
tests="$root/app/src/test/java/de/kontrakurs/kontraprompter/video/PcmTeeTest.kt"
java -XX:ActiveProcessorCount=1 -Xmx160m -cp "$compiler:$stdlib:$script:$reflect:$daemon:$coroutines:$annotations" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib:$annotations:$junit:$hamcrest" \
  -d "$temporary" "$src" "$tests"
java -Xmx64m -cp "$temporary:$stdlib:$junit:$hamcrest" org.junit.runner.JUnitCore de.kontrakurs.kontraprompter.video.PcmTeeTest
python3 - "$temporary" <<'PY'
import re,subprocess,sys
for name,method in [('de.kontrakurs.kontraprompter.video.PcmTee','publish'),('de.kontrakurs.kontraprompter.video.PcmTee$Subscription','offer')]:
    text=subprocess.check_output(['javap','-c','-p','-classpath',sys.argv[1],name],text=True)
    match=re.search(r'^  (?:public|private).*\b'+method+r'(?:\$[\w]+)?\([^\n]*\);\n(.*?)(?=^  (?:public|private|protected)|\Z)',text,re.M|re.S)
    if not match: raise SystemExit('PCM hot-path bytecode missing: '+name+'.'+method)
    body=match.group(1)
    if re.search(r'\b(?:new|newarray|anewarray|multianewarray)\b|Arrays\.copyOf|ByteBuffer\.wrap|ReentrantLock\.lock:',body):
        raise SystemExit('unexpected allocation or blocking lock in PCM hot path: '+name+'.'+method)
    if method=='offer' and 'ReentrantLock.tryLock:' not in body: raise SystemExit('nonblocking lock check missing')
    print('PCM hot path has no direct allocation or blocking lock:',name+'.'+method)
PY
