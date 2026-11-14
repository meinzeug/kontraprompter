#!/usr/bin/env bash
set -euo pipefail
# Test-only shell-context DEX; never packages app secrets or user keys.
output=${1:?Pass a NEW absolute path for the DEX jar}
[[ "$output" = /* && ! -e "$output" ]] || { echo 'Output must be an absent absolute path' >&2; exit 1; }
android_root=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-"$HOME/Android/Sdk"}
cache=${GRADLE_USER_HOME:-"$HOME/.gradle"}/caches/modules-2/files-2.1
shopt -s globstar nullglob
find_one() {
  local paths=( $1 )
  if ((${#paths[@]} != 1)); then printf 'Expected one cached dependency: %s\n' "$1" >&2; return 1; fi
  printf '%s' "${paths[0]}"
}
compiler=$(find_one "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/**/*.jar")
stdlib=$(find_one "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/**/*.jar")
compiler_stdlib=$(find_one "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/**/*.jar")
script=$(find_one "$cache/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/**/*.jar")
reflect=$(find_one "$cache/org.jetbrains.kotlin/kotlin-reflect/1.6.10/**/*.jar")
daemon=$(find_one "$cache/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.2.20/**/*.jar")
coroutines=$(find_one "$cache/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/**/*.jar")
annotations=$(find_one "$cache/org.jetbrains/annotations/13.0/**/*.jar")
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/kontraprompter-android-tls.XXXXXXXX")
trap 'rm -rf -- "$fixture_dir"' EXIT
mkdir "$fixture_dir/classes" "$fixture_dir/dex"
java -Xmx256m -cp "$compiler:$compiler_stdlib:$script:$reflect:$daemon:$coroutines:$annotations" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -language-version 2.0 -api-version 2.0 -no-stdlib -no-reflect -classpath "$stdlib:$annotations:$sdk/platforms/android-36/android.jar" \
  -d "$fixture_dir/classes" \
  "$android_root/app/src/main/java/de/kontrakurs/kontraprompter/network/PinnedPairingTls.kt" \
  "$android_root/app/src/test/java/de/kontrakurs/kontraprompter/pairing/AndroidKeystoreTlsProbe.kt"
jar cf "$fixture_dir/probe.jar" -C "$fixture_dir/classes" .
"$sdk/build-tools/35.0.0/d8" -JXmx256M --min-api 26 --lib "$sdk/platforms/android-36/android.jar" \
  --output "$fixture_dir/dex" "$fixture_dir/probe.jar" "$stdlib" 2>"$fixture_dir/d8.log" || { head -40 "$fixture_dir/d8.log" >&2; exit 1; }
if [[ -s "$fixture_dir/d8.log" ]]; then head -20 "$fixture_dir/d8.log" >&2; exit 1; fi
mkdir -p "$(dirname "$output")"
jar cf "$output" -C "$fixture_dir/dex" classes.dex
printf 'Created %s\n' "$output"
