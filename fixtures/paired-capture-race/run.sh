#!/usr/bin/env bash
set -euo pipefail

fixture_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$fixture_dir/../.." && pwd)
cache_dir="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
output_dir="${KOTLIN_FIXTURE_OUTPUT:-/tmp/kontraprompter-paired-capture-race}"
shopt -s nullglob
compiler=("$cache_dir"/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.20/*/*.jar)
stdlib=("$cache_dir"/org.jetbrains.kotlin/kotlin-stdlib/2.2.20/*/*.jar)
script=("$cache_dir"/org.jetbrains.kotlin/kotlin-script-runtime/2.2.20/*/*.jar)
reflect=("$cache_dir"/org.jetbrains.kotlin/kotlin-reflect/2.0.21/*/*.jar)
coroutines=("$cache_dir"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar)
annotations=("$cache_dir"/org.jetbrains/annotations/23.0.0/*/*.jar)
junit=("$cache_dir"/junit/junit/4.13.2/*/*.jar)
hamcrest=("$cache_dir"/org.hamcrest/hamcrest-core/1.3/*/*.jar)
for dependency in compiler stdlib script reflect coroutines annotations junit hamcrest; do
  declare -n paths="$dependency"
  if (( ${#paths[@]} != 1 )); then echo "Missing or ambiguous dependency: $dependency" >&2; exit 1; fi
  unset -n paths
done
mkdir -p -- "$output_dir"
java -Xmx192m -XX:ActiveProcessorCount=2 -cp "${compiler[0]}:${stdlib[0]}:${script[0]}:${reflect[0]}:${coroutines[0]}:${annotations[0]}" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "${stdlib[0]}:${coroutines[0]}:${annotations[0]}:${junit[0]}:${hamcrest[0]}" -d "$output_dir" \
  "$repo_dir/app/src/main/java/de/kontrakurs/kontraprompter/video/PairedCapture.kt" \
  "$repo_dir/app/src/main/java/de/kontrakurs/kontraprompter/video/PairedCapturePolicy.kt" \
  "$fixture_dir/Json.kt" "$fixture_dir/LanStub.kt" "$fixture_dir/VideoStub.kt" "$fixture_dir/RaceTest.kt"
java -Xmx96m -XX:ActiveProcessorCount=2 -cp "$output_dir:${stdlib[0]}:${coroutines[0]}:${annotations[0]}:${junit[0]}:${hamcrest[0]}" \
  org.junit.runner.JUnitCore de.kontrakurs.kontraprompter.video.RaceTest
