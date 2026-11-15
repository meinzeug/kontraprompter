# Navigation policy: isolated preparation

This is a pure, currently **unconnected** policy sketch. The existing Activity,
Back handlers, recording, session and upload behavior are not changed. It is
not a guarantee of recording or upload safety in the running app.

The ordered, nonempty stack and destinations are typed. A request plans a
stack transition without taking any side effect. Leaving the upload screen or
exiting while upload is running/cancelling is blocked; an uncertain upload may
be left but this policy never retries it or declares success. A transition that
would close the connection or exit is blocked while a local/remote take is
active or unknown, including when a session reference is already absent. A
live session itself requires explicit closure first. A client can open scripts
from the connection page without closing its session. These conservative
choices are proposals; current UI behavior remains unchanged.

Before integration the caller must map real recorder, upload and session states
(including finalizing, failed/unknown, cancellation and process recreation)
conservatively and prevent bypass via any menu, role change, lifecycle, home or
predictive-back path. An observed remote state can be stale or unavailable; it
must not be treated as proof that a remote take ended. Explicitly preserve the
original on an upload cancellation and its uncertain server result.

A planned result carries the stack and the full supplied snapshot, including a
lifecycle epoch, so it can be rejected when the data change. Merely calling
`revalidate` and then navigating is **not atomic**: integration must bind the
snapshot check and the actual transition to the relevant domain ownership and
lifecycle synchronization, including an already-running callback, across all
real entry points. Epoch generation must resist identity reuse. This fixture does
not prove these requirements, provide session closure/stop/cancel mechanisms,
allow bypass based on a confirmation, or implement predictive-back animations.
A completed or cancelled back gesture must not act on a stale plan.

Unit tests cover stack order, active/unknown local and remote takes, interrupted
session ownership, active/cancelling upload, uncertainty and stale snapshots.
They are host-only tests; device, Compose, actual recorder/upload and lifecycle
interleavings require separate instrumented and two-device verification.
