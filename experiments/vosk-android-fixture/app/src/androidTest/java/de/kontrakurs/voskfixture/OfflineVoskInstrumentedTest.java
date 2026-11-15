package de.kontrakurs.voskfixture;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

/** Separate test APK. No microphone, network, real recording or app integration. */
@RunWith(AndroidJUnit4.class)
public class OfflineVoskInstrumentedTest {
  private static final String ASSET = "vosk-model-small-de-0.15.zip";
  private static final String SHA = "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43";
  private static final long ZIP_BYTES = 46_499_967L;
  private static final long MAX_EXTRACTED = 110L * 1024 * 1024;
  private static final String SYNTHETIC_ASSET = "synthetic-de-48khz-s16le.pcm";
  private static final int SYNTHETIC_BYTES = 556_732;
  private static final String SYNTHETIC_SHA = "cfcfe20034a34e79326187276cd8d5225284655239d82e24f65b6d0b5d9cc311";
  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }
  private static void noCaptureOrNetworkPermission(Context context) throws Exception {
    PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
    if (info.requestedPermissions != null) for (String permission : info.requestedPermissions) {
      assertFalse("microphone permission is forbidden in fixture", "android.permission.RECORD_AUDIO".equals(permission));
      assertFalse("network permission is forbidden in fixture", "android.permission.INTERNET".equals(permission));
    }
  }
  private static BoundedPcmDecoder decoder(Model model) throws Exception {
    Recognizer recognizer = new Recognizer(model, 48_000f);
    try { return new BoundedPcmDecoder(new JnaVoskPcmEngine(recognizer), 8); }
    catch (RuntimeException | LinkageError error) { recognizer.close(); throw error; }
  }

  @Test public void decodeSyntheticSilenceAndGermanWithoutAnotherMicrophone() throws Exception {
    Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Context test = InstrumentationRegistry.getInstrumentation().getContext();
    noCaptureOrNetworkPermission(target); noCaptureOrNetworkPermission(test);
    MessageDigest digest = MessageDigest.getInstance("SHA-256"); long size = 0;
    try (InputStream input = test.getAssets().open(ASSET)) {
      byte[] buffer = new byte[8192]; int count;
      while ((count = input.read(buffer)) != -1) {
        size += count; assertTrue("unexpected model ZIP size", size <= ZIP_BYTES); digest.update(buffer, 0, count);
      }
    }
    assertEquals(ZIP_BYTES, size); assertEquals(SHA, hex(digest.digest()));
    // Fresh private test-app cache directory; no shared or user files are touched.
    File root = Files.createTempDirectory(target.getCacheDir().toPath(), "vosk-probe-").toFile();
    String prefix = root.getCanonicalPath() + File.separator;
    Set<String> names = new HashSet<>(); long extracted = 0; int entries = 0;
    try (ZipInputStream zip = new ZipInputStream(test.getAssets().open(ASSET))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        assertTrue("model entry quota", ++entries <= 256);
        File path = new File(root, entry.getName()); String canonical = path.getCanonicalPath();
        assertTrue("model path outside private directory", canonical.startsWith(prefix));
        assertTrue("duplicate model path", names.add(canonical));
        if (entry.isDirectory()) { assertTrue(path.isDirectory() || path.mkdirs()); continue; }
        File parent = path.getParentFile(); assertTrue(parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream out = new FileOutputStream(path)) {
          byte[] buffer = new byte[8192]; int count;
          while ((count = zip.read(buffer)) != -1) {
            extracted += count; assertTrue("model extraction quota", extracted <= MAX_EXTRACTED); out.write(buffer, 0, count);
          }
        }
      }
    }
    File modelDir = new File(root, "vosk-model-small-de-0.15"); assertTrue(modelDir.isDirectory());
    LibVosk.vosk_set_log_level(-1);
    MessageDigest pcmDigest = MessageDigest.getInstance("SHA-256"); int pcmSize = 0;
    try (InputStream pcm = test.getAssets().open(SYNTHETIC_ASSET)) {
      byte[] block = new byte[2048]; int n;
      while ((n = pcm.read(block)) != -1) { pcmSize += n; assertTrue(pcmSize <= SYNTHETIC_BYTES); pcmDigest.update(block, 0, n); }
    }
    assertEquals(SYNTHETIC_BYTES, pcmSize); assertEquals(SYNTHETIC_SHA, hex(pcmDigest.digest()));
    try (Model model = new Model(modelDir.getAbsolutePath())) {
      try (BoundedPcmDecoder decoder = decoder(model)) {
        // One second of generated silence in bounded PCM16 mono blocks; no microphone.
        byte[] silence = new byte[2048]; int remaining = 96_000; long firstSample = 0;
        while (remaining > 0) {
          int n = Math.min(silence.length, remaining); remaining -= n;
          assertTrue(decoder.offer(silence, n, firstSample, 48_000)); firstSample += n / 2;
          BoundedPcmDecoder.Result result = decoder.pump(); assertNotEquals(BoundedPcmDecoder.Kind.FAILED, result.kind);
          if (result.kind == BoundedPcmDecoder.Kind.FINAL) assertEquals("", new JSONObject(result.json).optString("text", ""));
        }
        assertEquals("", new JSONObject(decoder.finish()).optString("text", ""));
      }
      try (BoundedPcmDecoder decoder = decoder(model);
           DataInputStream pcm = new DataInputStream(test.getAssets().open(SYNTHETIC_ASSET))) {
        StringBuilder text = new StringBuilder(); byte[] block = new byte[2048]; int remaining = SYNTHETIC_BYTES; long firstSample = 0;
        while (remaining > 0) {
          int n = Math.min(block.length, remaining); pcm.readFully(block, 0, n); remaining -= n;
          assertTrue(decoder.offer(block, n, firstSample, 48_000)); firstSample += n / 2;
          BoundedPcmDecoder.Result result = decoder.pump(); assertNotEquals(BoundedPcmDecoder.Kind.FAILED, result.kind);
          if (result.kind == BoundedPcmDecoder.Kind.FINAL) text.append(' ').append(new JSONObject(result.json).optString("text", ""));
        }
        text.append(' ').append(new JSONObject(decoder.finish()).optString("text", ""));
        String normalized = text.toString().toLowerCase(java.util.Locale.GERMANY)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        // Known machine-generated fixture text; not a human-speech accuracy metric.
        assertEquals("hallo zusammen heute sprechen wir über medien und videos", normalized);
      }
      SyntheticSpeechPipeline.run(model, test);
    }
  }
}
