package de.kontrakurs.voskfixture;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Isolated fixture only; never attached to the app or a microphone. */
public final class BoundedPcmDecoder implements AutoCloseable {
  interface Engine extends AutoCloseable {
    int accept(byte[] pcm);
    String result();
    String finalResult();
    @Override void close();
  }
  public enum Kind { EMPTY, CONTINUE, FINAL, FAILED }
  public static final class Result {
    final Kind kind; final String json;
    Result(Kind kind, String json) { this.kind = kind; this.json = json; }
  }
  private static final int MAX_PCM_BYTES = 8192;
  private static final int MAX_JSON_CHARS = 65_536;
  private final Engine engine;
  private final Thread worker = Thread.currentThread();
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
  private final AtomicReference<String> failure = new AtomicReference<>();
  private volatile boolean closed;
  private volatile boolean sealed;
  private long nextSample = -1;

  BoundedPcmDecoder(Engine engine, int capacity) {
    if (engine == null || capacity < 1 || capacity > 16) throw new IllegalArgumentException();
    this.engine = engine; this.capacity = capacity;
  }
  private void failed(String reason) { failure.compareAndSet(null, reason); }
  String failure() { return failure.get(); }
  /** Producer never waits for the analysis queue; a rejected block disables analysis. */
  boolean offer(byte[] pcm, int length, long firstSample, int sampleRate) {
    if (closed || sealed || failure.get() != null) return false;
    if (pcm == null || length < 2 || length > MAX_PCM_BYTES || length > pcm.length || (length & 1) != 0 || firstSample < 0 || sampleRate != 48_000) {
      failed("invalid_pcm"); return false;
    }
    if (!lock.tryLock()) { failed("queue_contended"); return false; }
    try {
      if (closed || sealed || failure.get() != null) return false;
      if (nextSample >= 0 && nextSample != firstSample) { failed("pcm_gap"); return false; }
      if (queue.size() >= capacity) { failed("queue_full"); return false; }
      final long next;
      try { next = Math.addExact(firstSample, length / 2); }
      catch (ArithmeticException error) { failed("pcm_overflow"); return false; }
      try { queue.addLast(Arrays.copyOf(pcm, length)); }
      catch (OutOfMemoryError error) { failed("analysis_memory"); return false; }
      nextSample = next;
      return true;
    } finally { lock.unlock(); }
  }
  private void checkWorker() { if (Thread.currentThread() != worker) throw new IllegalStateException("decoder worker changed"); }
  private String checkedJson(String value) {
    if (value == null || value.length() > MAX_JSON_CHARS) { failed("invalid_result"); return null; }
    return value;
  }
  Result pump() {
    checkWorker();
    if (closed || failure.get() != null) return new Result(Kind.FAILED, null);
    byte[] block;
    lock.lock(); try { block = queue.pollFirst(); } finally { lock.unlock(); }
    if (block == null) return new Result(Kind.EMPTY, null);
    try {
      int status = engine.accept(block);
      if (status != 0 && status != 1) { failed("native_status"); return new Result(Kind.FAILED, null); }
      if (failure.get() != null) return new Result(Kind.FAILED, null);
      if (status == 0) return new Result(Kind.CONTINUE, null);
      String json = checkedJson(engine.result());
      return failure.get() == null ? new Result(Kind.FINAL, json) : new Result(Kind.FAILED, null);
    } catch (RuntimeException | LinkageError error) { failed("native_failure"); return new Result(Kind.FAILED, null); }
  }
  String finish() {
    checkWorker();
    if (closed || failure.get() != null) return null;
    lock.lock(); try { sealed = true; if (!queue.isEmpty()) { failed("pending_pcm"); return null; } } finally { lock.unlock(); }
    try { String result = checkedJson(engine.finalResult()); return failure.get() == null ? result : null; }
    catch (RuntimeException | LinkageError error) { failed("native_failure"); return null; }
  }
  @Override public void close() {
    checkWorker();
    if (closed) return;
    closed = true;
    lock.lock(); try { queue.clear(); } finally { lock.unlock(); }
    try { engine.close(); } catch (RuntimeException | LinkageError error) { failed("native_cleanup"); }
  }
}
