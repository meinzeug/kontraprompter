package de.kontrakurs.voskfixture;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class BoundedPcmDecoderTest {
  static final class Fake implements BoundedPcmDecoder.Engine {
    int status; int calls; int closes; byte[] seen; boolean throwOnAccept; String json = "{\"text\":\"synthetic\"}";
    @Override public int accept(byte[] pcm) { calls++; seen = pcm; if (throwOnAccept) throw new IllegalStateException(); return status; }
    @Override public String result() { return json; }
    @Override public String finalResult() { return json; }
    @Override public void close() { closes++; }
  }
  @Test public void nativeNegativeAndUnexpectedStatusCannotBecomeFinal() {
    for (int status : new int[] { -1, 2, Integer.MIN_VALUE }) {
      Fake fake = new Fake(); fake.status = status;
      try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 2)) {
        assertTrue(decoder.offer(new byte[] {1, 0}, 2, 0, 48_000));
        assertEquals(BoundedPcmDecoder.Kind.FAILED, decoder.pump().kind);
        assertEquals("native_status", decoder.failure()); assertNull(decoder.finish());
      }
      assertEquals(1, fake.closes);
    }
  }
  @Test public void nativeZeroAndOneHaveDistinctMeaningAndOwnCopy() {
    Fake fake = new Fake(); byte[] original = {1, 0};
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 2)) {
      assertTrue(decoder.offer(original, 2, 10, 48_000)); original[0] = 9;
      assertEquals(BoundedPcmDecoder.Kind.CONTINUE, decoder.pump().kind); assertEquals(1, fake.seen[0]);
      fake.status = 1; assertTrue(decoder.offer(original, 2, 11, 48_000));
      BoundedPcmDecoder.Result result = decoder.pump(); assertEquals(BoundedPcmDecoder.Kind.FINAL, result.kind);
      assertEquals(fake.json, result.json); assertEquals(fake.json, decoder.finish());
      assertFalse(decoder.offer(original, 2, 12, 48_000));
    }
  }
  @Test public void queueOverflowInvalidatesEvenPreviouslyQueuedAudio() {
    Fake fake = new Fake();
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 1)) {
      assertTrue(decoder.offer(new byte[2], 2, 0, 48_000));
      assertFalse(decoder.offer(new byte[2], 2, 1, 48_000));
      assertEquals("queue_full", decoder.failure()); assertEquals(BoundedPcmDecoder.Kind.FAILED, decoder.pump().kind); assertEquals(0, fake.calls);
    }
  }
  @Test public void gapOrBadFormatStopsAnalysisOnly() {
    Fake fake = new Fake();
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 2)) {
      assertTrue(decoder.offer(new byte[2], 2, 0, 48_000)); assertFalse(decoder.offer(new byte[2], 2, 2, 48_000));
      assertEquals("pcm_gap", decoder.failure());
    }
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(new Fake(), 2)) {
      assertFalse(decoder.offer(new byte[3], 3, 0, 48_000)); assertEquals("invalid_pcm", decoder.failure());
    }
  }
  @Test public void nativeExceptionAndOversizedResultFailClosed() {
    Fake fake = new Fake(); fake.throwOnAccept = true;
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 2)) {
      assertTrue(decoder.offer(new byte[2], 2, 0, 48_000)); assertEquals(BoundedPcmDecoder.Kind.FAILED, decoder.pump().kind);
      assertEquals("native_failure", decoder.failure());
    }
    Fake large = new Fake(); large.status = 1; char[] chars = new char[65_537]; java.util.Arrays.fill(chars, 'x'); large.json = new String(chars);
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(large, 2)) {
      assertTrue(decoder.offer(new byte[2], 2, 0, 48_000)); assertEquals(BoundedPcmDecoder.Kind.FAILED, decoder.pump().kind);
      assertEquals("invalid_result", decoder.failure());
    }
  }
  @Test(timeout = 5000) public void queueDropDuringNativeDecodeSuppressesResult() throws Exception {
    CountDownLatch started = new CountDownLatch(1), done = new CountDownLatch(1);
    BoundedPcmDecoder.Engine fake = new BoundedPcmDecoder.Engine() {
      public int accept(byte[] pcm) { started.countDown(); try { if (!done.await(2, TimeUnit.SECONDS)) throw new IllegalStateException(); } catch (InterruptedException e) { throw new IllegalStateException(e); } return 1; }
      public String result() { return "{}"; } public String finalResult() { return "{}"; } public void close() {}
    };
    try (BoundedPcmDecoder decoder = new BoundedPcmDecoder(fake, 1)) {
      assertTrue(decoder.offer(new byte[2], 2, 0, 48_000));
      Thread producer = new Thread(() -> { try { if (!started.await(1, TimeUnit.SECONDS)) return; decoder.offer(new byte[2], 2, 1, 48_000); decoder.offer(new byte[2], 2, 2, 48_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
      producer.setDaemon(true); producer.start();
      assertEquals(BoundedPcmDecoder.Kind.FAILED, decoder.pump().kind); producer.join(2000); assertFalse(producer.isAlive());
    }
  }
}
