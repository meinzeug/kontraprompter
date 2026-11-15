package org.webrtc;

/** Only the owning camera thread may decide whether its session is still active. */
final class KpCameraClose {
  private KpCameraClose() {}
  static void notifyIfActive(boolean stopped, Runnable notification) {
    if (!stopped) notification.run();
  }
}
