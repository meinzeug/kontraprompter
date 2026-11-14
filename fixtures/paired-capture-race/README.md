# PairedCapture race fixture

This small JVM fixture compiles the real `PairedCapture` and policy source with simulated LAN, JSON, and camera dependencies. It exercises admission of a command while the recording state or connection changes. It does not test Android networking, WebRTC, physical buttons, or real JSON serialization.

Run `bash fixtures/paired-capture-race/run.sh` from any directory. The script uses the existing Gradle dependency cache; it does not download dependencies or run Gradle. `GRADLE_USER_HOME` may point to a different cache, and `KOTLIN_FIXTURE_OUTPUT` may specify an output directory. The Kotlin compiler heap is limited to 192 MiB.
