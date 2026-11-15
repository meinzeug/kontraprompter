package de.kontrakurs.voskfixture;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import org.vosk.Recognizer;

/** Test-only C int binding: the upstream boolean wrapper cannot preserve -1. */
final class JnaVoskPcmEngine implements BoundedPcmDecoder.Engine {
  private final Recognizer recognizer;
  private final Function accept;
  JnaVoskPcmEngine(Recognizer recognizer) {
    this.recognizer = recognizer;
    this.accept = NativeLibrary.getInstance("vosk").getFunction("vosk_recognizer_accept_waveform");
  }
  @Override public int accept(byte[] pcm) { return accept.invokeInt(new Object[] { recognizer.getPointer(), pcm, pcm.length }); }
  @Override public String result() { return recognizer.getResult(); }
  @Override public String finalResult() { return recognizer.getFinalResult(); }
  @Override public void close() { recognizer.close(); }
}
