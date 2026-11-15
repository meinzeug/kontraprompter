package org.webrtc;

import java.util.IdentityHashMap;

/** Synthetic callback adapter only. Camera/recorder proofs are NOT connected to Android. */
final class KpDeferredCallbackFixture {
  interface CloseDevice { void close(Object device) throws Exception; }
  private static final int HISTORY_LIMIT = 64;
  private static final class Device {
    final Object owner;
    final Object value;
    boolean closeStarted;
    boolean closeReturned;
    boolean closedCallback;
    Device(Object owner, Object value) { this.owner = owner; this.value = value; }
  }
  private final KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup();
  private final IdentityHashMap<Object, Device> history = new IdentityHashMap<>();
  private Object current;
  private Device currentDevice;
  private boolean stopping;

  synchronized Object acquire() {
    if (history.size() >= HISTORY_LIMIT) { gate.poison(); return null; }
    Object owner = gate.tryAcquire();
    if (owner != null) { current = owner; currentDevice = null; stopping = false; }
    return owner;
  }
  synchronized boolean issueOpen(Object owner) { return gate.openIssued(owner); }
  synchronized boolean noOpen(Object owner) { return gate.noOpen(owner); }
  synchronized boolean recorderDone(Object owner) { return gate.recorderProved(owner); }
  synchronized boolean stopDone(Object owner) { return gate.stopProved(owner); }
  synchronized boolean cleanupDone(Object owner) { return gate.cleanupProved(owner); }
  synchronized boolean timeout(Object owner) { return gate.markTimeout(owner); }
  synchronized boolean blocked() { return gate.isBlocked(); }

  void stop(Object owner, CloseDevice closer) {
    Device action;
    synchronized (this) {
      if (!gate.requestStop(owner)) return;
      stopping = true;
      action = startClose(currentDevice);
    }
    closeOutsideLock(action, closer);
  }

  /** `owner` stands for a trusted callback instance bound to the issued open. */
  void arrived(Object owner, Object camera, CloseDevice closer) {
    Device action = null;
    synchronized (this) {
      Device known = history.get(camera);
      // Never close an object already assigned to a different generation.
      if (known != null && known.owner != owner) { gate.poison(); return; }
      if (known != null) return; // duplicate callback for the exact object
      // An unidentifiable stale callback is quarantined, not guessed to be safe.
      if (owner != current || camera == null || history.size() >= HISTORY_LIMIT) { gate.poison(); return; }
      Device device = new Device(owner, camera);
      history.put(camera, device);
      KpDeferredCameraCleanup.Arrival result = gate.cameraArrived(owner, camera);
      if (result == KpDeferredCameraCleanup.Arrival.ADOPT) currentDevice = device;
      else if (result == KpDeferredCameraCleanup.Arrival.CLOSE_LATE) { currentDevice = device; action = startClose(device); }
      else if (result == KpDeferredCameraCleanup.Arrival.REJECT) { gate.poison(); action = startClose(device); }
      // A normal callback can race requestStop; both transitions share this lock.
      if (result == KpDeferredCameraCleanup.Arrival.ADOPT && stopping) action = startClose(device);
    }
    closeOutsideLock(action, closer);
  }

  synchronized void closed(Object owner, Object camera) {
    Device device = history.get(camera);
    if (device == null || device.owner != owner) { gate.poison(); return; }
    if (device.closedCallback) return;
    device.closedCallback = true;
    finishClose(device);
  }
  private Device startClose(Device device) {
    if (device == null || device.closeStarted) return null;
    device.closeStarted = true;
    return device;
  }
  private void closeOutsideLock(Device device, CloseDevice closer) {
    if (device == null) return;
    try {
      closer.close(device.value);
      synchronized (this) { device.closeReturned = true; finishClose(device); }
    } catch (Exception e) {
      synchronized (this) { gate.poison(); }
    }
  }
  /** A close callback alone is not proof while the close call is still in flight. */
  private void finishClose(Device device) {
    if (device.closeStarted && device.closeReturned && device.closedCallback && device.owner == current)
      gate.cameraClosed(device.owner, device.value);
  }
}
