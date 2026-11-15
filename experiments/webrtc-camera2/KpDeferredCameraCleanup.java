package org.webrtc;

/**
 * Isolated, fail-closed ownership model; not connected to Camera2 or the app.
 * All proof inputs must come from a trusted, generation-bound integration. No
 * callback, camera operation, wait, recorder operation or other I/O runs here.
 */
final class KpDeferredCameraCleanup {
  enum Arrival { ADOPT, CLOSE_LATE, DUPLICATE, REJECT }
  private enum Open { UNDECIDED, NOT_ISSUED, ISSUED }
  private Object owner;
  private Open open;
  private Object camera;
  private boolean cameraClosed;
  private boolean stopping;
  private boolean cleanupProved;
  private boolean recorderProved;
  private boolean stopProved;
  private boolean timedOut;
  private boolean poisoned;

  synchronized Object tryAcquire() {
    if (owner != null || poisoned) return null;
    owner = new Object(); open = Open.UNDECIDED; camera = null;
    cameraClosed = stopping = cleanupProved = recorderProved = stopProved = timedOut = false;
    return owner;
  }

  /** Must precede the actual call to openCamera; uncertainty never clears it. */
  synchronized boolean openIssued(Object token) {
    if (!owns(token) || stopping || open != Open.UNDECIDED || poisoned) return false;
    open = Open.ISSUED;
    return true;
  }

  /** Only valid when no open was issued and none can be issued later. */
  synchronized boolean noOpen(Object token) {
    if (!owns(token) || open != Open.UNDECIDED || poisoned) return false;
    open = Open.NOT_ISSUED;
    releaseIfProved();
    return true;
  }

  synchronized boolean requestStop(Object token) {
    if (!owns(token) || poisoned) return false;
    stopping = true;
    return true;
  }

  /** CLOSE_LATE tells the caller to close outside this monitor. */
  synchronized Arrival cameraArrived(Object token, Object incoming) {
    if (!owns(token) || poisoned || open != Open.ISSUED || incoming == null) return Arrival.REJECT;
    if (camera == incoming) return Arrival.DUPLICATE;
    if (camera != null) { poisoned = true; return Arrival.REJECT; }
    camera = incoming;
    return stopping ? Arrival.CLOSE_LATE : Arrival.ADOPT;
  }

  synchronized boolean cameraClosed(Object token, Object actualCamera) {
    if (!owns(token) || poisoned || camera == null || camera != actualCamera || cameraClosed) return false;
    cameraClosed = true;
    releaseIfProved();
    return true;
  }

  /** Proof refers only to non-camera pipeline resources. */
  synchronized boolean cleanupProved(Object token) {
    if (!owns(token) || poisoned || !stopping || cleanupProved) return false;
    cleanupProved = true;
    releaseIfProved();
    return true;
  }

  /** Includes successful recorder worker settlement or proof that none existed. */
  synchronized boolean recorderProved(Object token) {
    if (!owns(token) || poisoned || !stopping || recorderProved) return false;
    recorderProved = true;
    releaseIfProved();
    return true;
  }

  /** WebRTC stop returned normally, without interruption, or was never needed. */
  synchronized boolean stopProved(Object token) {
    if (!owns(token) || poisoned || !stopping || stopProved) return false;
    stopProved = true;
    releaseIfProved();
    return true;
  }

  /** Timeout is diagnostic only; it must never manufacture release proof. */
  synchronized boolean markTimeout(Object token) {
    if (!owns(token) || poisoned) return false;
    timedOut = true;
    return true;
  }

  /** An uncertain/failed cleanup permanently blocks this process-local model. */
  synchronized void poison() { poisoned = true; }
  synchronized boolean isBlocked() { return poisoned || owner != null; }
  synchronized boolean timedOut(Object token) { return owns(token) && timedOut; }
  private boolean owns(Object token) { return token != null && owner == token; }
  private void releaseIfProved() {
    if (stopping && cleanupProved && recorderProved && stopProved &&
        (open == Open.NOT_ISSUED || (open == Open.ISSUED && cameraClosed))) owner = null;
  }
}
