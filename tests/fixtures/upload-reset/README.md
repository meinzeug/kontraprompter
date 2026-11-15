# Interrupted upload status fixture

Run `python3 tests/fixtures/upload-reset/run.py [git-revision]` with the
matching Kotlin and coroutines jars in the local Gradle cache. It compiles the
revision's real upload coordinator/client/journal, using explicit host Android,
JSON, filesystem and HTTPS stubs. It does not access a network or device and
cleans up only its own synthetic temporary files. The simulated connection
throws while the app sends data; the five cases cover confirmed tombstone,
older server (404), different conflict, malformed response, and a 204 response.
Each checks journal phase and that the synthetic original remains unchanged.
It does not prove Android's HTTP/TLS stack, real streaming, server routing,
concurrent session handling, or Android AtomicFile behavior.

`python3 tests/fixtures/upload-reset/run-loopback.py [git-revision]` additionally
uses real loopback TCP: a synthetic server resets the upload socket mid-stream,
then serves status 409 or 404 on a separate connection. The actual client source
runs on the host JVM with Android stubs, not on a smartphone, and HTTP is local;
this is not a TLS, Android networking or real Next.js/server end-to-end test.
