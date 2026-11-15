// Original experiment; no camera side effects. WebRTC's FramerateRange unit is 0.001 fps.
package org.webrtc;

import java.util.List;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat.FramerateRange;

final class KpFixedFpsSelector {
  private KpFixedFpsSelector() {}

  static FramerateRange select(List<FramerateRange> advertised, int requestedFps) {
    // Null means the explicitly requested fixed rate cannot be safely offered.
    if (advertised == null || requestedFps <= 0 || requestedFps > Integer.MAX_VALUE / 1000) {
      return null;
    }
    final int requestedMilliFps = requestedFps * 1000;
    for (FramerateRange candidate : advertised) {
      if (candidate != null && candidate.min == requestedMilliFps && candidate.max == requestedMilliFps) {
        return candidate;
      }
    }
    return null;
  }
}
