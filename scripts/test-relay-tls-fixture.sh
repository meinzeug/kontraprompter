#!/usr/bin/env bash
set -euo pipefail
# Local synthetic keys and classes only; never use Android or production keys.
android_root=$(cd "$(dirname "$0")/.." && pwd)
web_root=${1:?Pass the Videomaker worktree containing relay-pinned-tls-http-fixture.test.mjs}
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
fixture_dir=$(mktemp -d "${TMPDIR:-/tmp}/kontraprompter-tls-fixture.XXXXXXXX")
trap 'rm -rf -- "$fixture_dir"' EXIT
mkdir "$fixture_dir/classes"
for identity in camera client; do
  keytool -genkeypair -alias device -keyalg EC -groupname secp256r1 -dname "CN=synthetic-$identity" \
    -validity 1 -storetype PKCS12 -keystore "$fixture_dir/$identity.p12" \
    -storepass fixture-only -keypass fixture-only -noprompt >/dev/null 2>&1
done
java -Xmx256m -cp "$compiler:$stdlib:$script:$reflect:$daemon:$coroutines:$annotations" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib:$annotations" \
  -d "$fixture_dir/classes" \
  "$android_root/app/src/main/java/de/kontrakurs/kontraprompter/network/PinnedPairingTls.kt" \
  "$android_root/app/src/main/java/de/kontrakurs/kontraprompter/pairing/RelayByteBridge.kt" \
  "$android_root/app/src/test/java/de/kontrakurs/kontraprompter/pairing/RelayTlsHttpProbe.kt"
cd "$web_root"
RELAY_TLS_HTTP_TEST=1 RELAY_TLS_PROBE_CP="$fixture_dir/classes:$stdlib" \
  RELAY_TLS_CAMERA_KEYSTORE="$fixture_dir/camera.p12" RELAY_TLS_CLIENT_KEYSTORE="$fixture_dir/client.p12" \
  node --experimental-strip-types --test tests/relay-pinned-tls-http-fixture.test.mjs
