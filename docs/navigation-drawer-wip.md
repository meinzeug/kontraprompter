# Drawer UI integration candidate — not released

This candidate is based on `b9b2e76`. A Material 3 modal drawer on compact windows and a permanent drawer on windows
at least 840 dp wide expose only a closed list of page-only destinations. It cannot be opened in the connection,
prompter or upload pages, while hosting the camera, or while the shared upload
coordinator reports a running transfer. The permanently nested containers keep the active content composition at the
same location when the available width or drawer gate changes. Buttons use text
labels, selected-item semantics from Material and decorative vector icons; the
list scrolls on short windows and at large text scales. The modal sheet takes
the real drawer state so Material handles Back. Its opening job is cancelled and
the drawer closed when the gate becomes invalid or the window becomes wide. Unknown routes fail closed. It does not provide a
new global backstack, predictive-back animation or destructive menu actions.

The camera role no longer silently replaces an existing session; the user is
asked to disconnect it first. The check also runs before the camera permission
request and again when it returns. System Back at the roles screen now stays
in the app if a session exists. On the connection page Back/Disconnect no
longer silently stops a local active/finalizing recording. `LiveSession.closeIfIdle`
admits closure using the same guard used to mark recording as preparing, and
refuses if its current recording remains active; close/codec I/O occurs outside
the admission lock. The explicit stop control and existing lifecycle `close()`
remain unchanged, so Android `ON_STOP` still closes the session as before.

A client is not disconnected by Back/Disconnect while its reported remote
status is unknown, active, failed or pending; connected idle/ready/partial are
accepted. When disconnected with unknown status, an explicit dialog asks the
user to inspect the camera and acknowledges that recording may continue before
leaving the session. The dialog rechecks that the session is still the same and
checks the current state again. **That client-side status is an asynchronous snapshot, not an atomic
remote recorder fence.** A delayed start/status or disconnect can race this
check. Therefore this UI is **not approved for production**, and no claim is
made that remote capture is protected from every transition. A real shared
channel/session-generation admission mechanism, late command/ACK and remote
status race tests are still needed. Other entry points and system/Home/ON_STOP
continue to have their previous semantics and need independent review.

The upload screen owns its coroutine and retains its existing Back behavior;
it is never reachable through a new drawer path while visible. The coordinator
state is only a UI hint, not proof that a network worker has finished or that a
server receipt is durable. The code neither retries uploads nor moves ownership.
A fresh check is made when a menu item is clicked, but does not constitute an
atomic global lifecycle fence.

Eight local JVM tests exercise the real drawer gate functions and the exact
shared idle-close admission helper under controlled lock interleavings. They do
not instantiate `LiveSession`, real WebRTC/codec or Compose; do not prove
Android lifecycle, UI focus, network or two-device behavior. Debug/release CI,
Compose/accessibility/large-window review and isolated real-device tests are
required before any merge or deployment. The previous independent policy model
is not wired into this UI and is not represented as a safety mechanism here.
