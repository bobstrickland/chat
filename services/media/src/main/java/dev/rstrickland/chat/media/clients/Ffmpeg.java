package dev.rstrickland.chat.media.clients;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Video/audio processing by shelling out to the ffmpeg CLI (installed in the
 * image). Every op is best-effort: any failure (bad input, timeout, non-zero
 * exit) returns null and the caller falls back to the original bytes.
 *
 * Scale filter caps the LONGEST side at the max while preserving aspect ratio
 * and only ever downscaling (`min(iw,MAX)`/`min(ih,MAX)` +
 * force_original_aspect_ratio=decrease); force_divisible_by=2 keeps dimensions
 * even for h264.
 */
final class Ffmpeg {

  private static final long TIMEOUT_SECONDS = 120;

  private Ffmpeg() {}

  /** Shrink video to <=1024 on the longest side, re-encode h264/aac mp4. */
  static byte[] shrinkVideo(byte[] input) {
    return run(
        input, "in", "mp4",
        List.of(),
        List.of(
            "-vf", "scale='min(iw,1024)':'min(ih,1024)':force_original_aspect_ratio=decrease:force_divisible_by=2",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "28",
            "-c:a", "aac", "-b:a", "128k",
            "-movflags", "+faststart", "-f", "mp4"));
  }

  /** Extract a frame ~1s in as a <=400px JPEG. */
  static byte[] videoThumbnail(byte[] input) {
    byte[] thumb =
        run(
            input, "in", "jpg",
            List.of("-ss", "1"), // fast-seek before -i
            List.of(
                "-frames:v", "1",
                "-vf", "scale='min(iw,400)':'min(ih,400)':force_original_aspect_ratio=decrease",
                "-f", "image2"));
    if (thumb != null) {
      return thumb;
    }
    // Very short video: seek to 1s failed — grab the first frame instead.
    return run(
        input, "in", "jpg",
        List.of(),
        List.of(
            "-frames:v", "1",
            "-vf", "scale='min(iw,400)':'min(ih,400)':force_original_aspect_ratio=decrease",
            "-f", "image2"));
  }

  /** Re-encode audio to <=128 kbps mp3. */
  static byte[] shrinkAudio(byte[] input) {
    return run(input, "in", "mp3", List.of(), List.of("-b:a", "128k", "-f", "mp3"));
  }

  private static byte[] run(
      byte[] input, String inExt, String outExt, List<String> preInput, List<String> postInput) {
    Path in = null;
    Path out = null;
    try {
      in = Files.createTempFile("media-in-", "." + inExt);
      out = Files.createTempFile("media-out-", "." + outExt);
      Files.write(in, input);

      List<String> cmd = new ArrayList<>();
      cmd.add("ffmpeg");
      cmd.add("-y");
      cmd.addAll(preInput);
      cmd.add("-i");
      cmd.add(in.toString());
      cmd.addAll(postInput);
      cmd.add(out.toString());

      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      byte[] log = p.getInputStream().readAllBytes(); // drain so it can't block
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        System.err.println("[media] ffmpeg timed out");
        return null;
      }
      if (p.exitValue() != 0) {
        System.err.println("[media] ffmpeg exit " + p.exitValue() + ": " + tail(log));
        return null;
      }
      byte[] result = Files.readAllBytes(out);
      return result.length > 0 ? result : null;
    } catch (Exception e) {
      System.err.println("[media] ffmpeg error: " + e.getMessage());
      return null;
    } finally {
      deleteQuietly(in);
      deleteQuietly(out);
    }
  }

  private static String tail(byte[] log) {
    String s = new String(log, java.nio.charset.StandardCharsets.UTF_8);
    return s.length() > 300 ? s.substring(s.length() - 300) : s;
  }

  private static void deleteQuietly(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (Exception ignored) {
        // best effort
      }
    }
  }
}
