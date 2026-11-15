#!/usr/bin/env bash
set -euo pipefail
# Synthetic-only JVM test: no microphone, model download, network, or device.
root=$(cd "$(dirname "$0")/.." && pwd)
cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1
shopt -s globstar nullglob
find_one() {
  local paths=( $1 )
  if ((${#paths[@]} != 1)); then printf 'Expected one cached dependency: %s\n' "$1" >&2; return 1; fi
  printf '%s' "${paths[0]}"
}
compiler=$(find_one "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/**/*.jar")
stdlib=$(find_one "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/**/*.jar")
script=$(find_one "$cache/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/**/*.jar")
reflect=$(find_one "$cache/org.jetbrains.kotlin/kotlin-reflect/1.6.10/**/*.jar")
daemon=$(find_one "$cache/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20/**/*.jar")
coroutines=$(find_one "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/**/*.jar")
annotations=$(find_one "$cache/org.jetbrains/annotations/13.0/**/*.jar")
junit=$(find_one "$cache/junit/junit/4.13.2/**/*.jar")
hamcrest=$(find_one "$cache/org.hamcrest/hamcrest-core/1.3/**/*.jar")
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/kp-speech-tests.XXXXXXXX")
trap 'rm -rf -- "$fixture_dir"' EXIT
src="$root/app/src/main/java/de/kontrakurs/kontraprompter"
tests="$root/app/src/test/java/de/kontrakurs/kontraprompter"
java -XX:ActiveProcessorCount=1 -Xmx160m -cp "$compiler:$stdlib:$script:$reflect:$daemon:$coroutines:$annotations" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib:$annotations:$junit:$hamcrest" \
  -d "$fixture_dir" "$src/video/PcmTee.kt" "$src/speech/SpokenScript.kt" \
  "$tests/video/PcmTeeTest.kt" "$tests/speech/SpokenScriptTest.kt" "$tests/speech/SpeechPcmFixtureTest.kt" \
  "$tests/speech/SpeechFollowHarness.kt" "$tests/speech/SpeechAtomicPositionFixture.kt" "$tests/speech/SpeechFollowHarnessTest.kt"
java -Xmx64m -cp "$fixture_dir:$stdlib:$junit:$hamcrest" org.junit.runner.JUnitCore \
  de.kontrakurs.kontraprompter.video.PcmTeeTest \
  de.kontrakurs.kontraprompter.speech.SpokenScriptTest \
  de.kontrakurs.kontraprompter.speech.SpeechPcmFixtureTest \
  de.kontrakurs.kontraprompter.speech.SpeechFollowHarnessTest
