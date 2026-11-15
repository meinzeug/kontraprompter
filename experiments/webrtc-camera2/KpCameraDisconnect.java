package org.webrtc;

/** Must run on the same owner thread as the session state. */
final class KpCameraDisconnect {
  private KpCameraDisconnect() {}
  static void dispatch(boolean stopped, boolean startupReported, Runnable closeLate,
      Runnable adopt, Runnable markStopped, Runnable cleanup, Runnable startupFailure, Runnable disconnected) {
    if (stopped) { closeLate.run(); return; }
    adopt.run();
    markStopped.run();
    cleanup.run();
    if (startupReported) disconnected.run(); else startupFailure.run();
  }
}
