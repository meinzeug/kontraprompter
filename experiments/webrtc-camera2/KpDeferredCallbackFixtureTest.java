package org.webrtc;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class KpDeferredCallbackFixtureTest {
  @Test public void lateOpenClosesOnceAndNeedsAllFourProofs() {
    KpDeferredCallbackFixture f = new KpDeferredCallbackFixture(); Object owner = f.acquire(), camera = new Object();
    AtomicInteger closed = new AtomicInteger();
    assertTrue(f.issueOpen(owner)); f.stop(owner, c -> fail()); assertTrue(f.timeout(owner));
    assertTrue(f.recorderDone(owner)); assertTrue(f.stopDone(owner)); assertTrue(f.cleanupDone(owner));
    f.arrived(owner, camera, c -> { assertSame(camera, c); closed.incrementAndGet(); });
    assertNull(f.acquire()); f.arrived(owner, camera, c -> fail()); assertEquals(1, closed.get());
    f.closed(owner, camera); f.closed(owner, camera); assertNotNull(f.acquire());
  }

  @Test(timeout=5000) public void callbackDuringBlockedCloseCannotReleaseOrBlockOtherChecks() throws Exception {
    KpDeferredCallbackFixture f = new KpDeferredCallbackFixture(); Object owner = f.acquire(), camera = new Object();
    assertTrue(f.issueOpen(owner)); f.arrived(owner, camera, c -> fail());
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Thread closer = new Thread(() -> f.stop(owner, c -> { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); }));
    closer.setDaemon(true); closer.start();
    try {
      assertTrue(entered.await(1, TimeUnit.SECONDS)); f.closed(owner, camera);
      assertTrue(f.recorderDone(owner)); assertTrue(f.stopDone(owner)); assertTrue(f.cleanupDone(owner));
      assertNull(f.acquire()); assertTrue(f.blocked());
    } finally { release.countDown(); closer.join(2000); }
    assertFalse(closer.isAlive()); assertNotNull(f.acquire());
  }

  @Test public void unexpectedSecondCameraIsClosedButPoisonsGate() {
    KpDeferredCallbackFixture f = new KpDeferredCallbackFixture(); Object owner = f.acquire(), first = new Object(), second = new Object();
    assertTrue(f.issueOpen(owner)); f.arrived(owner, first, c -> fail());
    AtomicInteger closed = new AtomicInteger(); f.arrived(owner, second, c -> { assertSame(second, c); closed.incrementAndGet(); });
    assertEquals(1, closed.get()); assertNull(f.acquire()); assertTrue(f.blocked());
  }

  @Test public void staleCallbackCannotCloseNewOwnersCamera() {
    KpDeferredCallbackFixture f = new KpDeferredCallbackFixture(); Object old = f.acquire(), first = new Object();
    assertTrue(f.issueOpen(old)); f.arrived(old, first, c -> fail()); f.stop(old, c -> {}); f.closed(old, first);
    assertTrue(f.recorderDone(old)); assertTrue(f.stopDone(old)); assertTrue(f.cleanupDone(old));
    Object next = f.acquire(), nextCamera = new Object(); assertNotNull(next); assertTrue(f.issueOpen(next));
    f.arrived(next, nextCamera, c -> fail());
    f.arrived(old, nextCamera, c -> fail("must never close new owner's camera"));
    assertTrue(f.blocked()); assertNull(f.acquire());
  }

  @Test public void failedCloseAndMissingCallbackFailClosed() {
    KpDeferredCallbackFixture failed = new KpDeferredCallbackFixture(); Object a = failed.acquire(), camera = new Object();
    assertTrue(failed.issueOpen(a)); failed.arrived(a, camera, c -> fail());
    failed.stop(a, c -> { throw new Exception("synthetic failure"); });
    failed.closed(a, camera); assertNull(failed.acquire());
    KpDeferredCallbackFixture absent = new KpDeferredCallbackFixture(); Object b = absent.acquire();
    assertTrue(absent.issueOpen(b)); absent.stop(b, c -> fail()); assertTrue(absent.timeout(b));
    assertTrue(absent.recorderDone(b)); assertTrue(absent.stopDone(b)); assertTrue(absent.cleanupDone(b)); assertNull(absent.acquire());
  }
}
