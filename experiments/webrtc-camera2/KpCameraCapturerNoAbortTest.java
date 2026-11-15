package org.webrtc;

import static org.junit.Assert.*;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** JVM-only probe of the pinned WebRTC class. Reflection is confined to this fixture. */
public class KpCameraCapturerNoAbortTest {
  static final class FixtureCapturer extends CameraCapturer {
    FixtureCapturer() { super("fixture", null, null); }
    @Override protected void createCameraSession(CameraSession.CreateSessionCallback callback,
        CameraSession.Events events, Context context, SurfaceTextureHelper helper,
        String cameraName, int width, int height, int framerate) {}
  }

  @Test(timeout = 6000) public void stopWaitsForAnUnresolvedOpenAndInterruptDoesNotCompleteIt() throws Exception {
    // No Android constructor or device is used: set only the two private fields
    // needed to reproduce the library's existing synchronized wait loop.
    Class<?> type = Class.forName("sun.misc.Unsafe");
    Field singleton = type.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
    Object unsafe = singleton.get(null);
    Method allocate = type.getMethod("allocateInstance", Class.class);
    FixtureCapturer capturer = (FixtureCapturer) allocate.invoke(unsafe, FixtureCapturer.class);
    Field lock = CameraCapturer.class.getDeclaredField("stateLock"); lock.setAccessible(true); lock.set(capturer, new Object());
    Field opening = CameraCapturer.class.getDeclaredField("sessionOpening"); opening.setAccessible(true); opening.setBoolean(capturer, true);
    AtomicBoolean timeoutReported = new AtomicBoolean();
    CameraVideoCapturer.CameraEventsHandler events = new CameraVideoCapturer.CameraEventsHandler() {
      @Override public void onCameraError(String error) { timeoutReported.set(true); }
      @Override public void onCameraDisconnected() {}
      @Override public void onCameraFreezed(String error) {}
      @Override public void onCameraOpening(String name) {}
      @Override public void onFirstFrameAvailable() {}
      @Override public void onCameraClosed() {}
    };
    Field handler = CameraCapturer.class.getDeclaredField("eventsHandler"); handler.setAccessible(true); handler.set(capturer, events);
    Class<?> timerType = Class.forName("org.webrtc.CameraCapturer$3");
    Constructor<?> timerConstructor = timerType.getDeclaredConstructor(CameraCapturer.class); timerConstructor.setAccessible(true);
    Runnable timeout = (Runnable) timerConstructor.newInstance(capturer);
    CountDownLatch started = new CountDownLatch(1); AtomicBoolean returned = new AtomicBoolean();
    AtomicBoolean preservedInterrupt = new AtomicBoolean();
    Thread worker = new Thread(() -> { started.countDown(); capturer.stopCapture();
      preservedInterrupt.set(Thread.currentThread().isInterrupted()); returned.set(true); }, "synthetic-capturer-stop");
    worker.setDaemon(true); worker.start();
    try {
      assertTrue(started.await(1, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (worker.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(5);
      assertEquals(Thread.State.WAITING, worker.getState());
      assertFalse("stop must remain waiting while the open has no callback", returned.get());
      timeout.run();
      assertTrue("the actual WebRTC timeout reports an error", timeoutReported.get());
      assertEquals("reporting timeout does not wake or resolve the pending open", Thread.State.WAITING, worker.getState());
      assertTrue(opening.getBoolean(capturer));
    } finally { worker.interrupt(); worker.join(2000); }
    assertFalse(worker.isAlive()); assertTrue(returned.get()); assertTrue(preservedInterrupt.get());
    assertTrue("interrupt does not resolve the still-pending open", opening.getBoolean(capturer));
  }
}
