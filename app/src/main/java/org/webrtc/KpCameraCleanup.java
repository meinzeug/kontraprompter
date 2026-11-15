package org.webrtc;

import java.util.concurrent.atomic.AtomicBoolean;

/** Fail closed across experimental sessions when camera cleanup is uncertain. */
final class KpCameraCleanup {
  private static final KpCameraCleanup SHARED = new KpCameraCleanup();
  private final AtomicBoolean blocked = new AtomicBoolean();
  static KpCameraCleanup shared() { return SHARED; }
  boolean isBlocked() { return blocked.get(); }

  boolean release(Runnable... releases) {
    boolean complete = true;
    for (Runnable release : releases) {
      if (release == null) continue;
      try { release.run(); } catch (RuntimeException error) { complete = false; }
    }
    if (!complete) blocked.set(true);
    return complete;
  }
}
