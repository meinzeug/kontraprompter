# Separates Android-v4-Gerätefixture

Test-only branch on `6ad6470`. Its package is always
`de.kontrakurs.kontraprompter.journalv4fixture`, including non-debug builds;
it cannot update the regular app. The fixture client points only to HTTPS
`127.0.0.1:3395`, with its own test CA and an isolated server. The synthetic
Keystore identity, project and journal belong exclusively to the test package.
The regular application, its data and production server are not test targets.

`CameraLocalE2eTest` exercises device registration, ticket, a real HTTP poll,
the real journal and synthetic recorder callbacks. It simulates loss of a poll
response after the server accepted an `enqueued` ACK and verifies the durable
ACK remains pending and is retried. It does not activate camera or microphone,
start a real recorder or establish production readiness. Its separate source
changes and test CA must not be cherry-picked into the production app.

A dedicated Web runner and a CI build are required; before any device run,
check the recording state, fixture APK hashes/package names, certificate and
pre-existing ADB-reverse rules. Remove only fixture-owned packages and reverse
rules and verify the regular phone connection afterwards. No device test is
claimed until an actual run is documented.

On 15 November 2026, CI `43692046702` built the app and test APK from
`563df32` (CI commit `d973e2d`). The isolated test passed on Samsung SM-A526B
(13.561 s) and Xiaomi 2409BRN2CY (16.012 s; Wi-Fi ADB). It sent an `enqueued`
ACK to the local server, deliberately discarded the resulting response and
verified that the device journal kept the ACK pending until an idempotent
retry. Recorder `Recording`/`Failed` events were synthetic. The first Xiaomi
installation attempt was rejected by MIUI; the separate identified fixture
was subsequently installed after confirming only its own system dialog.

The test packages were removed from both phones and their ADB-reverse lists
were empty before and after. The local server and own test data were removed;
the normal apps remained in the foreground with the camera link and Studio
connection reported afterwards. This does not prove a real recorder admission,
actual video/audio, Android process kill mid-command, production HTTPS or
production activation. No user recording or main app data was changed.
