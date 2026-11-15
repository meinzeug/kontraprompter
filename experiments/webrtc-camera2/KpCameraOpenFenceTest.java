package org.webrtc;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class KpCameraOpenFenceTest {
  @Test public void oldCloseCannotReleaseNewOwner() {
    KpCameraOpenFence gate = new KpCameraOpenFence(); Object old = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.markOpenIssued(old)); assertEquals(KpCameraOpenFence.Arrival.ACCEPT, gate.cameraArrived(old, camera));
    assertTrue(gate.cleanupFinished(old, true)); assertNull(gate.tryAcquire());
    assertTrue(gate.onCameraClosed(old, camera)); Object next = gate.tryAcquire(); assertNotNull(next);
    assertFalse(gate.onCameraClosed(old, camera)); assertNull(gate.tryAcquire());
  }
  @Test public void stoppedBeforeOpenWaitsForLateDeviceAndItsClose() {
    KpCameraOpenFence gate = new KpCameraOpenFence(); Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.markOpenIssued(owner)); assertTrue(gate.cleanupFinished(owner, true)); assertNull(gate.tryAcquire());
    assertEquals(KpCameraOpenFence.Arrival.CLOSE_LATE, gate.cameraArrived(owner, camera));
    assertFalse(gate.onCameraClosed(owner, new Object())); assertNull(gate.tryAcquire());
    assertTrue(gate.onCameraClosed(owner, camera)); assertNotNull(gate.tryAcquire());
  }
  @Test public void noIssuedOpenCanReleaseButUnknownCleanupCannot() {
    KpCameraOpenFence gate = new KpCameraOpenFence(); Object first = gate.tryAcquire();
    assertTrue(gate.cleanupFinished(first, true)); Object next = gate.tryAcquire(); assertNotNull(next);
    assertTrue(gate.cleanupFinished(next, false)); assertNull(gate.tryAcquire()); assertTrue(gate.isBlocked());
    assertFalse(gate.cleanupFinished(next, true)); assertNull(gate.tryAcquire());
  }
  @Test public void closedBeforeCleanupDoesNotReleaseUntilCleanupIsConfirmed() {
    KpCameraOpenFence gate = new KpCameraOpenFence(); Object owner = gate.tryAcquire(), camera = new Object();
    assertTrue(gate.markOpenIssued(owner)); assertEquals(KpCameraOpenFence.Arrival.ACCEPT, gate.cameraArrived(owner, camera));
    assertTrue(gate.onCameraClosed(owner, camera)); assertNull(gate.tryAcquire());
    assertTrue(gate.cleanupFinished(owner, true)); assertNotNull(gate.tryAcquire());
  }
  @Test public void acquisitionIsAtomicAcrossConcurrentThreads() throws Exception {
    KpCameraOpenFence gate = new KpCameraOpenFence(); AtomicInteger successes = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1); List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Thread thread = new Thread(() -> { try { start.await(); if (gate.tryAcquire() != null) successes.incrementAndGet(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
      threads.add(thread); thread.start();
    }
    start.countDown(); for (Thread thread : threads) thread.join(2000);
    for (Thread thread : threads) assertFalse(thread.isAlive());
    assertEquals(1, successes.get());
  }
  @Test public void lateCleanupFailurePoisonsEvenWithoutCurrentOwner() {
    KpCameraOpenFence gate = new KpCameraOpenFence(); Object first = gate.tryAcquire();
    assertTrue(gate.cleanupFinished(first, true)); gate.poison();
    assertNull(gate.tryAcquire()); assertTrue(gate.isBlocked());
  }
}
