package dev.rstrickland.chat.media.clients;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.coobird.thumbnailator.Thumbnails;

/** Image shrink + thumbnail via ImageIO/Thumbnailator — pure JVM, no ffmpeg. */
final class ImageOps {

  private static final int MAX = 1024; // longest side of the STORED image
  private static final int THUMB = 400; // longest side of the thumbnail

  private ImageOps() {}

  /**
   * Resize so the longest side is <= 1024, preserving aspect ratio. Returns the
   * bytes UNCHANGED if it's already within bounds (no needless re-encode), and
   * null if the bytes aren't a decodable image.
   */
  static byte[] shrink(byte[] bytes) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null) {
        return null;
      }
      if (image.getWidth() <= MAX && image.getHeight() <= MAX) {
        return bytes; // already small enough
      }
      String format = detectFormat(bytes);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(image).size(MAX, MAX).outputFormat(format).toOutputStream(out);
      return out.toByteArray();
    } catch (Exception e) {
      return null;
    }
  }

  /** 400px-max JPEG thumbnail, or null if the bytes aren't a decodable image. */
  static byte[] thumbnail(byte[] bytes) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
      if (image == null) {
        return null;
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(image).size(THUMB, THUMB).outputFormat("jpg").toOutputStream(out);
      return out.toByteArray();
    } catch (Exception e) {
      return null;
    }
  }

  /** ImageIO/Thumbnailator writer format name ("png"/"jpg"/…); "jpg" as a fallback. */
  private static String detectFormat(byte[] bytes) {
    try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (readers.hasNext()) {
        String name = readers.next().getFormatName().toLowerCase();
        return name.equals("jpeg") ? "jpg" : name; // png / gif / bmp / jpg
      }
    } catch (Exception ignored) {
      // fall through
    }
    return "jpg";
  }
}
