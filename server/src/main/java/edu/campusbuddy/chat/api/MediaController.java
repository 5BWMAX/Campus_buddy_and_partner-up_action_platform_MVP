package edu.campusbuddy.chat.api;

import edu.campusbuddy.chat.infrastructure.MediaRepository;
import edu.campusbuddy.shared.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
  private static final long MAX_BYTES = 5L * 1024 * 1024;

  private final String uploadDir;
  private final MediaRepository media;

  public MediaController(
      @Value("${campus.upload-dir:.local/uploads}") String dir, MediaRepository media) {
    this.uploadDir = dir;
    this.media = media;
  }

  @PostMapping("/upload")
  public ResponseEntity<Row> upload(
      HttpServletRequest r, @RequestParam(value = "file", required = false) MultipartFile file)
      throws IOException {
    UUID actor = HttpSupport.actor(r);
    Problem.require(file != null && !file.isEmpty(), 400, "VALIDATION_FAILED", "文件不能为空");
    Problem.require(file.getSize() <= MAX_BYTES, 400, "VALIDATION_FAILED", "图片大小不能超过 5MB");

    byte[] bytes = file.getBytes();
    Problem.require(bytes.length <= MAX_BYTES, 400, "VALIDATION_FAILED", "图片大小不能超过 5MB");
    String contentType = detect(bytes);
    Problem.require(
        contentType != null,
        400,
        "INVALID_IMAGE",
        "仅支持 JPEG、PNG、GIF 或 WebP 图片");

    String extension =
        switch (contentType) {
          case "image/jpeg" -> ".jpg";
          case "image/png" -> ".png";
          case "image/gif" -> ".gif";
          default -> ".webp";
        };
    UUID id = UUID.randomUUID();
    String fileName = id.toString().replace("-", "") + extension;
    Path dir = Paths.get(uploadDir);
    Files.createDirectories(dir);
    Files.write(dir.resolve(fileName), bytes);

    String original = originalName(file.getOriginalFilename(), extension);
    media.record(id, actor, original, contentType, bytes.length, fileName);
    return HttpSupport.ok(
        Row.of("url", "/uploads/" + fileName, "fileName", fileName, "contentType", contentType, "size", bytes.length));
  }

  private String originalName(String value, String extension) {
    String name = value == null ? "" : value.replace('\\', '/');
    int slash = name.lastIndexOf('/');
    if (slash >= 0) name = name.substring(slash + 1);
    if (name.isBlank()) name = "image" + extension;
    return name.length() <= 255 ? name : name.substring(0, 255);
  }

  /** Validates the actual file signature instead of trusting the browser-supplied MIME type. */
  private String detect(byte[] b) {
    if (b.length >= 3
        && (b[0] & 0xff) == 0xff
        && (b[1] & 0xff) == 0xd8
        && (b[2] & 0xff) == 0xff) return "image/jpeg";
    if (b.length >= 8
        && (b[0] & 0xff) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && (b[4] & 0xff) == 0x0d
        && (b[5] & 0xff) == 0x0a
        && (b[6] & 0xff) == 0x1a
        && (b[7] & 0xff) == 0x0a) return "image/png";
    if (b.length >= 6
        && (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8')
        && (b[4] == '7' || b[4] == '9')
        && b[5] == 'a') return "image/gif";
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') return "image/webp";
    return null;
  }
}
