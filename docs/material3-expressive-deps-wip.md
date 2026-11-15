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
