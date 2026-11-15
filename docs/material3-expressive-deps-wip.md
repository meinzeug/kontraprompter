# Material Expressive dependency experiment

This isolated dependency step updates the Compose BOM from 2025.08.00 to
2026.09.00 and explicitly selects Material 3 1.5.0-rc01. The latter is a
release candidate, not a stable release, and requires compatibility and device
review before release. AGP, Gradle, Kotlin, and the Compose compiler remain
unchanged. No screen or app theme is changed in this commit.

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

The source jar `material3-android-1.5.0-rc01-sources.jar` downloaded from
Google Maven had SHA-256
`0e9c5b37e75203b44b4987b3ccc0db41637303103ca21739691d7533cbe7cbb1`.
Source API presence is not proof that the app can compile or run with it.
