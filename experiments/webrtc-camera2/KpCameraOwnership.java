package org.webrtc;

import java.util.function.Consumer;

/** Adopt a CameraDevice delivered before onOpened; an unexpected other device is closed separately. */
final class KpCameraOwnership {
  private KpCameraOwnership() {}
  static <T> T adopt(T current, T incoming, Consumer<T> closeUnexpected) {
    if (current == null) return incoming;
    if (incoming != null && current != incoming) closeUnexpected.accept(incoming);
    return current;
  }
}
