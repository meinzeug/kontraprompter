# Material Expressive dependency experiment

This isolated dependency step updates the Compose BOM from 2025.08.00 to
2026.09.00 and explicitly selects Material 3 1.5.0-rc01. The latter is a
release candidate, not a stable release, and requires compatibility and device
review before release. The dependency change and the required toolchain
migration are separate commits. No screen or app theme is changed here.

The corresponding Android sources published by Google contain public
`MaterialExpressiveTheme` and `MotionScheme.expressive()` APIs. Actual runtime
resolution and compatibility must be verified in the isolated CI build. Existing
screens use fixed light text and must remain on their current dark theme until
each has been migrated and checked, including camera/video overlays.

## Compatibility result

GitHub Actions run 43695794209, against dependency commit 2372188 with a
separate CI-only workflow commit, resolved `material3-android:1.5.0-rc01`
successfully. The BOM also selected Compose UI/Foundation/Runtime 1.12.1.
`checkDebugAarMetadata` then failed: Material3 itself and Compose 1.12.1
require compileSdk at least 37 and Android Gradle Plugin at least 9.1.0. The
current app uses compileSdk 36 and AGP 8.13.1. Debug assembly did not finish;
the subsequent unit tests and lint were not run. Kotlin/compiler compatibility
and runtime behavior therefore remain unverified. The source experiment must
not be integrated or released in this state. Do not suppress the AAR metadata
checks; a separately reviewed toolchain migration is required for this version.

The subsequent isolated migration uses AGP 9.1.1, Gradle 9.3.1 and compileSdk
37; minSdk 26 and targetSdk 36 remain unchanged. AGP 9's built-in Kotlin
replaces the Android Kotlin plugin, and the obsolete `android.kotlinOptions`
block is removed; JVM target continues to follow Java target 17. The Compose
compiler plugin remains explicitly at 2.2.20 pending compatibility testing.
The Gradle wrapper jar is checked against Gradle's published 9.3.1 checksum,
and the distribution checksum is pinned in wrapper properties.

GitHub Actions run 43696964747 used the same source tree as commit c93e92e,
plus only its CI workflow. Debug assembly succeeded, 224 JVM tests passed and
lint reported zero errors and 24 warnings. Buildscript resolution selected
Kotlin Gradle Plugin 2.2.20 (up from AGP's 2.2.10 default), and build logs
showed both the Kotlin compiler and Compose compiler plugin at 2.2.20. This is
build-time evidence, not a device or runtime compatibility test. Recorder
selection now rejects missing video capabilities; microphone permission is
rechecked at AudioRecord creation. The required superclass key dispatch has
one narrowly scoped lint suppression. Source/release review and actual
camera, recording, remote-control and accessibility tests remain necessary.

The source jar `material3-android-1.5.0-rc01-sources.jar` downloaded from
Google Maven had SHA-256
`0e9c5b37e75203b44b4987b3ccc0db41637303103ca21739691d7533cbe7cbb1`.
Source API presence is not proof that the app can compile or run with it.
