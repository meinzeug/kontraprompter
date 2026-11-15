package org.webrtc;

/** Isolated state-machine prototype, deliberately not wired to Android. No method blocks on I/O. */
final class KpCameraOpenFence {
  enum Arrival { ACCEPT, CLOSE_LATE, REJECT }
  private Object owner;
  private Object camera;
  private boolean issued;
  private boolean cleanupDone;
  private boolean closed;
  private boolean poisoned;

  synchronized Object tryAcquire() {
    if (owner != null || poisoned) return null;
    owner = new Object(); camera = null; issued = false; cleanupDone = false; closed = false;
    return owner;
  }
  synchronized boolean markOpenIssued(Object token) {
    if (!owns(token) || issued || cleanupDone || poisoned) return false;
    issued = true; return true;
  }
  synchronized Arrival cameraArrived(Object token, Object incoming) {
    if (!owns(token) || poisoned || !issued || incoming == null || camera != null) return Arrival.REJECT;
    camera = incoming;
    return cleanupDone ? Arrival.CLOSE_LATE : Arrival.ACCEPT;
  }
  synchronized boolean onCameraClosed(Object token, Object closedCamera) {
    if (!owns(token) || poisoned || camera == null || camera != closedCamera || closed) return false;
    closed = true; releaseIfSafe(); return true;
  }
  synchronized boolean cleanupFinished(Object token, boolean clean) {
    if (!owns(token) || cleanupDone) return false;
    cleanupDone = true;
    if (!clean) poisoned = true;
    else releaseIfSafe();
    return true;
  }
  /** A failure in a stale callback must still prevent future openings. */
  synchronized void poison() { poisoned = true; }
  synchronized boolean isBlocked() { return poisoned || owner != null; }
  private boolean owns(Object token) { return token != null && owner == token; }
  private void releaseIfSafe() {
    if (cleanupDone && (!issued || (camera != null && closed))) owner = null;
  }
}
