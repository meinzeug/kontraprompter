package org.webrtc;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class KpCameraLifecycleTest {
  private static final class FakeSession {
    boolean stopped;
    boolean started;
    boolean newListenerActive;
    int lateDevicesClosed;
    List<String> events = new ArrayList<>();
    void disconnect() {
      KpCameraDisconnect.dispatch(stopped, started, () -> lateDevicesClosed++, () -> {}, () -> stopped = true,
          () -> { newListenerActive = false; events.add("cleanup"); },
          () -> events.add("start-failure"), () -> events.add("disconnected"));
    }
  }
  @Test public void lateDisconnectDoesNotStopNewSessionListenerOrEmitDuplicate() {
    FakeSession s = new FakeSession(); s.started = true; s.disconnect();
    s.newListenerActive = true; s.disconnect();
    assertTrue(s.newListenerActive);
    assertEquals(1, s.lateDevicesClosed);
    assertEquals(Arrays.asList("cleanup", "disconnected"), s.events);
  }
  @Test public void stopOrErrorBeforeDisconnectNeverCallsCleanupAgain() {
    FakeSession s = new FakeSession(); s.stopped = true; s.newListenerActive = true;
    s.disconnect(); assertTrue(s.newListenerActive); assertTrue(s.events.isEmpty()); assertEquals(1, s.lateDevicesClosed);
  }
  @Test public void preStartDisconnectReportsFailureOnce() {
    FakeSession s = new FakeSession(); s.disconnect(); s.disconnect();
    assertEquals(Arrays.asList("cleanup", "start-failure"), s.events);
  }
  @Test public void errorBeforeOpenedAdoptsCameraForCleanupAndClosesUnexpectedDevice() {
    Object incoming = new Object(); Object other = new Object(); List<Object> closed = new ArrayList<>();
    Object owned = KpCameraOwnership.adopt(null, incoming, closed::add);
    assertSame(incoming, owned);
    assertSame(incoming, KpCameraOwnership.adopt(owned, other, closed::add));
    assertEquals(Arrays.asList(other), closed);
    KpCameraCleanup cleanup = new KpCameraCleanup();
    assertTrue(cleanup.release(() -> closed.add(owned)));
    assertEquals(Arrays.asList(other, incoming), closed);
  }
  @Test public void failingCloseDoesNotSkipOtherReleasesAndBlocksFurtherUse() {
    KpCameraCleanup cleanup = new KpCameraCleanup(); List<String> releases = new ArrayList<>();
    assertFalse(cleanup.release(() -> { releases.add("listener"); throw new IllegalStateException(); },
        () -> releases.add("session"), () -> { releases.add("surface"); throw new IllegalArgumentException(); },
        () -> releases.add("camera")));
    assertEquals(Arrays.asList("listener", "session", "surface", "camera"), releases);
    assertTrue(cleanup.isBlocked());
    assertTrue(cleanup.release(() -> releases.add("later")));
    assertTrue("failure stays fenced until process restart", cleanup.isBlocked());
  }
}
