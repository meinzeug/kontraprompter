package org.webrtc;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat.FramerateRange;

public class KpFixedFpsSelectorTest {
  @Test public void exactAdvertisedFixedRangeUsesWebRtcMillifps() {
    FramerateRange fallback = new FramerateRange(5000, 30000);
    FramerateRange fixed = new FramerateRange(20000, 20000);
    assertSame(fixed, KpFixedFpsSelector.select(Arrays.asList(fallback, fixed), 20));
  }

  @Test public void lowerUnitsAndFlexibleRangesCannotMasqueradeAsFixed20() {
    assertNull(KpFixedFpsSelector.select(Arrays.asList(
        new FramerateRange(20, 20), new FramerateRange(20000, 30000)), 20));
  }

  @Test public void malformedAndOverflowValuesFailClosed() {
    assertNull(KpFixedFpsSelector.select(null, 20));
    assertNull(KpFixedFpsSelector.select(Collections.singletonList(null), 20));
    assertNull(KpFixedFpsSelector.select(Collections.emptyList(), 0));
    assertNull(KpFixedFpsSelector.select(Collections.emptyList(), Integer.MAX_VALUE));
    assertNull(KpFixedFpsSelector.select(Collections.singletonList(
        new FramerateRange(30000, 20000)), 20));
  }
}
