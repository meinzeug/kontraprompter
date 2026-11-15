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
