# Navigation policy: isolated preparation

This pure model and its fence are **not connected** to the Activity, Compose,
recorder, session or upload. They do not protect the running app yet. No app
behavior, WebRTC session or predictive-back handling is changed by this WIP.

Destinations and requests are typed. A `SwitchRole` is classified as a session
replacement regardless of the visible page. Closing/replacing a session or
exiting is blocked if either take is active or unknown, including after the
session reference has disappeared; an existing session must be closed by the
real owner first. An unknown session is blocked until reconciled. A client can
open scripts without closing the current session. Mapping *every* real route,
role switch, system-back, lifecycle and activity exit to the correct request is
mandatory; an `Open` that hides a role replacement would bypass the model.

A running/cancelling upload only permits a screen transition when its actual
owner is known to survive it. A screen-owned upload blocks leaving its screen;
if that owner is reported while another screen is current, or ownership is
unknown, the model blocks rather than guessing. An activity-owned upload may
survive screen changes, but **the existing upload uses a screen-bound coroutine
and must not be labeled activity-owned**. Exiting remains blocked with an active
upload regardless of owner. An uncertain upload may be left; this model neither
retries it nor claims it succeeded. Unknown after process death is not the same
as a confirmed uncertain state: reconcile first and preserve the original.

A model-only `NavigationFence` serializes publication, planning, cancellation
and commit using one lock. Tickets are one-shot and bound to their fence,
current stack and full snapshot. A newer observation invalidates pending plans;
replayed/lower epochs are rejected. Thus a cancelled gesture cannot later commit,
a fresh plan supersedes an old one, and a pre-restore ticket cannot act on a
new fence. A restored model starts with session, takes, upload and owner unknown;
it cannot exit until the domain is reconciled. These are host-model properties.

**Integration boundary:** The real recorder, session, upload and UI do not use
this lock or epoch authority. A model commit and the subsequent real resource
operation are not atomic. Before integration every domain producer, lifecycle
change, owner-disposal and actual navigation/resource operation must be bound to
an enforceable common ordering/lease; stale remote observations cannot prove a
remote take ended. Do not hold a monitor during blocking I/O or codec work, and
never automatically repeat an unconfirmed upload or recording action. Merely
checking a ticket then acting later is insufficient. Model `SwitchRole` only
classifies a potential replacement; it does not close the old session, start a
new one or establish a safe transition. A dialog confirmation is no substitute
for completion and fresh state. Predictive-back progress is visual only until
commit; cancellation must invalidate the ticket and change no resources.

Host-only tests exercise stack order, real-effect classification for role
switching, screen/activity/unknown upload ownership, recording and upload
uncertainty, process restore, epoch replay, single-use tickets, gesture cancel
and synchronized publication versus commit. They do not prove real Compose,
Android lifecycle, native codec, server, device or multi-process behavior.
