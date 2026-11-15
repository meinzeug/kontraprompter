package org.webrtc;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class KpDeferredCameraCleanupTest {
  @Test public void lateOpenRequiresBothProofsAndMatchingClose() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup();
    Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.openIssued(owner)); assertTrue(gate.requestStop(owner));
    assertTrue(gate.cleanupProved(owner)); assertTrue(gate.markTimeout(owner));
    assertTrue(gate.timedOut(owner)); assertNull(gate.tryAcquire());
    assertEquals(KpDeferredCameraCleanup.Arrival.CLOSE_LATE, gate.cameraArrived(owner, camera));
    assertTrue(gate.cameraClosed(owner, camera)); assertTrue(gate.stopProved(owner)); assertNull(gate.tryAcquire());
    assertTrue(gate.recorderProved(owner)); assertNotNull(gate.tryAcquire());
  }

  @Test public void reorderedProofsAndDuplicateCallbacksFailClosed() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup();
    Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.openIssued(owner));
    assertFalse(gate.cameraClosed(owner, camera));
    assertEquals(KpDeferredCameraCleanup.Arrival.ADOPT, gate.cameraArrived(owner, camera));
    assertEquals(KpDeferredCameraCleanup.Arrival.DUPLICATE, gate.cameraArrived(owner, camera));
    assertTrue(gate.cameraClosed(owner, camera)); assertFalse(gate.cameraClosed(owner, camera));
    assertTrue(gate.requestStop(owner)); assertTrue(gate.recorderProved(owner)); assertTrue(gate.stopProved(owner));
    assertFalse(gate.recorderProved(owner)); assertNull(gate.tryAcquire());
    assertTrue(gate.cleanupProved(owner)); assertNotNull(gate.tryAcquire());
  }

  @Test public void wrongOwnerAndCameraCannotReleaseNewGeneration() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup();
    Object first = gate.tryAcquire(); assertTrue(gate.requestStop(first));
    assertTrue(gate.noOpen(first)); assertTrue(gate.cleanupProved(first)); assertTrue(gate.recorderProved(first)); assertTrue(gate.stopProved(first));
    Object second = gate.tryAcquire(), camera = new Object();
    assertNotSame(first, second); assertTrue(gate.openIssued(second)); assertTrue(gate.requestStop(second));
    assertEquals(KpDeferredCameraCleanup.Arrival.REJECT, gate.cameraArrived(first, camera));
    assertFalse(gate.cleanupProved(first)); assertFalse(gate.recorderProved(first));
    assertEquals(KpDeferredCameraCleanup.Arrival.CLOSE_LATE, gate.cameraArrived(second, camera));
    assertFalse(gate.cameraClosed(second, new Object())); assertTrue(gate.cleanupProved(second));
    assertTrue(gate.recorderProved(second)); assertTrue(gate.stopProved(second)); assertNull(gate.tryAcquire());
    assertTrue(gate.cameraClosed(second, camera)); assertNotNull(gate.tryAcquire());
  }

  @Test public void missingOpenDecisionOrRecorderNeverReleases() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup(); Object owner = gate.tryAcquire();
    assertTrue(gate.requestStop(owner)); assertTrue(gate.cleanupProved(owner)); assertTrue(gate.recorderProved(owner)); assertTrue(gate.stopProved(owner));
    assertTrue(gate.markTimeout(owner)); assertNull(gate.tryAcquire());
    assertTrue(gate.noOpen(owner)); assertNotNull(gate.tryAcquire());
    KpDeferredCameraCleanup other = new KpDeferredCameraCleanup(); Object another = other.tryAcquire();
    assertTrue(other.requestStop(another)); assertTrue(other.noOpen(another)); assertTrue(other.cleanupProved(another));
    assertTrue(other.markTimeout(another)); assertNull(other.tryAcquire());
  }

  @Test public void uncertainCleanupPoisonsEvenAfterAllOtherProofs() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup(); Object owner = gate.tryAcquire();
    assertTrue(gate.requestStop(owner)); assertTrue(gate.noOpen(owner)); assertTrue(gate.recorderProved(owner));
    gate.poison(); assertFalse(gate.cleanupProved(owner)); assertNull(gate.tryAcquire()); assertTrue(gate.isBlocked());
  }

  @Test public void stopMustBeProvedAfterAllOtherEvidence() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup(); Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.openIssued(owner)); assertTrue(gate.requestStop(owner));
    assertEquals(KpDeferredCameraCleanup.Arrival.CLOSE_LATE, gate.cameraArrived(owner, camera));
    assertTrue(gate.cameraClosed(owner, camera)); assertTrue(gate.cleanupProved(owner)); assertTrue(gate.recorderProved(owner));
    assertTrue(gate.markTimeout(owner)); assertNull(gate.tryAcquire());
    assertFalse(gate.stopProved(new Object())); assertNull(gate.tryAcquire());
    assertTrue(gate.stopProved(owner)); assertNotNull(gate.tryAcquire());
  }

  @Test public void unexpectedSecondCameraPoisonsInsteadOfReleasing() {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup(); Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.openIssued(owner)); assertEquals(KpDeferredCameraCleanup.Arrival.ADOPT, gate.cameraArrived(owner, camera));
    assertEquals(KpDeferredCameraCleanup.Arrival.REJECT, gate.cameraArrived(owner, new Object()));
    assertFalse(gate.cameraClosed(owner, camera)); assertNull(gate.tryAcquire()); assertTrue(gate.isBlocked());
  }

  @Test(timeout = 5000) public void competingOpensHaveOneWinner() throws Exception {
    KpDeferredCameraCleanup gate = new KpDeferredCameraCleanup(); int n = 32;
    CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(n);
    AtomicInteger winners = new AtomicInteger(); List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Thread thread = new Thread(() -> { try { start.await(); if (gate.tryAcquire() != null) winners.incrementAndGet(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
      thread.setDaemon(true); threads.add(thread); thread.start();
    }
    start.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, winners.get());
    for (Thread thread : threads) thread.join(100);
  }
}
