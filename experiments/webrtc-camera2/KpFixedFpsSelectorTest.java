package org.webrtc;

import static org.junit.Assert.assertSame;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat.FramerateRange;

public class KpFixedFpsSelectorTest {
  @Test public void exactAdvertisedFixedRangeUsesWebRtcMillifps() {
    FramerateRange fallback = new FramerateRange(5000, 30000);
    FramerateRange fixed = new FramerateRange(20000, 20000);
    assertSame(fixed, KpFixedFpsSelector.select(Arrays.asList(fallback, fixed), 20, fallback));
  }

  @Test public void lowerUnitsAndFlexibleRangesCannotMasqueradeAsFixed20() {
    FramerateRange fallback = new FramerateRange(5000, 30000);
    assertSame(fallback, KpFixedFpsSelector.select(Arrays.asList(
        new FramerateRange(20, 20), new FramerateRange(20000, 30000)), 20, fallback));
  }

  @Test public void malformedAndOverflowValuesFailToExistingSelection() {
    FramerateRange fallback = new FramerateRange(5000, 30000);
    assertSame(fallback, KpFixedFpsSelector.select(null, 20, fallback));
    assertSame(fallback, KpFixedFpsSelector.select(Collections.singletonList(null), 20, fallback));
    assertSame(fallback, KpFixedFpsSelector.select(Collections.emptyList(), 0, fallback));
    assertSame(fallback, KpFixedFpsSelector.select(Collections.emptyList(), Integer.MAX_VALUE, fallback));
    assertSame(fallback, KpFixedFpsSelector.select(Collections.singletonList(
        new FramerateRange(30000, 20000)), 20, fallback));
  }
}
