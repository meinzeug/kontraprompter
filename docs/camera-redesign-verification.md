# Camera redesign verification, 15 November 2026

Candidate `68de5f1` combines the camera/pairing redesign and drawer Back fix.
GitHub Actions run `43701616545` completed successfully: debug build and unit
tests, release build and `lintDebug`. The tests cover recorder-state-dependent
control availability and the drawer Back state policy; they do not execute a
real recorder, remote connection or Compose gesture.

On the Xiaomi `2409BRN2CY`, the debug APK from that run was verified against
the artifact ZIP SHA-256 and re-signed with the already installed app's signing
certificate. All 126 non-signature archive entries matched the CI candidate;
APK signature v2/v3 and ZIP alignment were verified. Before updating, the app
showed the inactive recording action and a prior upload conflict rather than a
running upload. A USB `adb install -r` succeeded, and the installed APK hash
matched the locally signed candidate. User data was not cleared. Installing the
update stopped the existing camera session; the app was then started again.

The drawer was opened on the Xiaomi and one Android Back key closed it, leaving
the start screen visible. The camera screen was opened without starting a
recording. Its UI hierarchy showed camera preparation, the offline connection
instruction, a disabled recording-start button, connection status and the
pairing section. The camera screen deliberately uses `FLAG_SECURE` to protect
the pairing secret, so no visual screenshot of that screen was taken or
inferred; the flag was not disabled. Large text, TalkBack, rotation and actual
recording states remain unverified on this candidate.

Both phones reported the same Wi-Fi SSID and reached the gateway; the computer
could reach each device. Direct phone-to-phone ping failed in both directions.
An actual direct Samsung-to-Xiaomi app connection attempt with the current
Wi-Fi address, port and 32-character code failed. The address and port were
verified in the client UI. No ADB reverse/relay was in use. Consequently this
run does not demonstrate a live image, end-to-end audio/video recording, or
remote start/pause/resume/stop. Client isolation or device filtering is a
possible cause, not a confirmed diagnosis. The failed connection did not start
an intentional recording.
