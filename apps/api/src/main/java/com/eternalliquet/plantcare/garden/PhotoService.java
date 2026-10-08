package com.eternalliquet.plantcare.garden;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class PhotoService {
  private static final int MAX_BYTES = 5 * 1024 * 1024;
  private final JdbcClient db;
  private final Path directory;
  private final URI identify;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final Semaphore capacity = new Semaphore(1);
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  record Candidate(String scientificName, double confidence) {}

  record Upload(
      UUID photoId,
      List<Candidate> candidates,
      String status,
      String modelVersion,
      String message) {}

  PhotoService(
      JdbcClient db,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.photo-directory:./data/photos}") String directory,
      @Value("${app.identify-url:http://127.0.0.1:8765/identify}") String identify)
      throws IOException {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    this.identify = URI.create(identify);
    if (!"http".equals(this.identify.getScheme())
        || !Set.of("127.0.0.1", "localhost", "[::1]").contains(this.identify.getHost()))
      throw new IllegalArgumentException(
          "The private identification service must use a loopback HTTP address.");
    PrivateStorage.ensureDirectory(this.directory);
  }

  Upload upload(UUID owner, byte[] original) throws IOException {
    if (!capacity.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Another photo is being checked. Try again in a moment.");
    try {
      prune();
      if (db.sql("SELECT COUNT(*) FROM garden_photos WHERE owner_id=:owner")
              .param("owner", owner)
              .query(Integer.class)
              .single()
          >= 200)
        throw new IllegalArgumentException("This garden has reached its 200-photo limit.");
      byte[] photo = sanitize(original);
      UUID id = UUID.randomUUID();
      Path path = path(id);
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      try {
        Files.write(path, photo, StandardOpenOption.WRITE);
      } catch (IOException failure) {
        Files.deleteIfExists(path);
        throw failure;
      }
      try {
        db.sql("INSERT INTO garden_photos(id,owner_id,created_at) VALUES(:id,:owner,:now)")
            .param("id", id)
            .param("owner", owner)
            .param("now", clock.instant())
            .update();
      } catch (RuntimeException failure) {
        Files.deleteIfExists(path);
        throw failure;
      }
      try {
        var request =
            HttpRequest.newBuilder(identify)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(photo))
                .build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
          response = pending.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
          pending.cancel(true);
          throw e;
        }
        if (response.statusCode() != 200 || response.body().length > 65536) return unavailable(id);
        JsonNode result = json.readTree(response.body());
        List<Candidate> candidates = new ArrayList<>();
        if (result.path("candidates").isArray())
          for (JsonNode candidate : result.path("candidates")) {
            String name = candidate.path("scientificName").asText("");
            double score = candidate.path("confidence").asDouble(-1);
            if (!name.isBlank()
                && name.length() <= 100
                && Double.isFinite(score)
                && score >= 0
                && score <= 1) candidates.add(new Candidate(name, score));
          }
        candidates.sort(Comparator.comparingDouble(Candidate::confidence).reversed());
        if (candidates.size() > 5) candidates = new ArrayList<>(candidates.subList(0, 5));
        String version = result.path("modelVersion").asText("");
        if (version.length() > 200) version = "";
        boolean uncertain =
            candidates.isEmpty() || !"suggestions".equals(result.path("status").asText());
        return new Upload(
            id,
            List.copyOf(candidates),
            uncertain ? "unknown" : "suggestions",
            version,
            uncertain
                ? "No confident match. These are only guesses; you can add a name yourself."
                : "Possible matches. Choose one, or enter your own name.");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return unavailable(id);
      } catch (Exception unavailable) {
        return unavailable(id);
      }
    } finally {
      capacity.release();
    }
  }

  private Upload unavailable(UUID id) {
    return new Upload(
        id,
        List.of(),
        "unavailable",
        "",
        "Photo saved. Identification isn't available right now. You can add a name yourself.");
  }

  byte[] read(UUID owner, UUID id) throws IOException {
    if (db.sql("SELECT id FROM garden_photos WHERE id=:id AND owner_id=:owner")
        .param("id", id)
        .param("owner", owner)
        .query(UUID.class)
        .optional()
        .isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found.");
    if (!Files.isRegularFile(path(id)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found.");
    return Files.readAllBytes(path(id));
  }

  void discard(UUID owner, UUID id) throws IOException {
    try {
      transactions.executeWithoutResult(
          tx -> {
            var row =
                db.sql("SELECT id FROM garden_photos WHERE id=:id AND owner_id=:owner FOR UPDATE")
                    .param("id", id)
                    .param("owner", owner)
                    .query(UUID.class)
                    .optional();
            if (row.isEmpty()) return;
            if (db.sql("SELECT COUNT(*) FROM plants WHERE photo_id=:id")
                    .param("id", id)
                    .query(Integer.class)
                    .single()
                > 0) return;
            try {
              Files.deleteIfExists(path(id));
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            // Delete bytes first. A failed database commit can only leave a harmless metadata row,
            // which the next cleanup can retry, never untracked retained image bytes.
            db.sql("DELETE FROM garden_photos WHERE id=:id AND owner_id=:owner")
                .param("id", id)
                .param("owner", owner)
                .update();
          });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  @Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
  void prune() {
    Instant cutoff = clock.instant().minus(Duration.ofHours(24));
    var expired =
        db.sql(
                "SELECT id,owner_id FROM garden_photos WHERE created_at<:cutoff AND NOT EXISTS"
                    + " (SELECT 1 FROM plants WHERE photo_id=garden_photos.id)")
            .param("cutoff", cutoff)
            .query(
                (r, n) ->
                    new UUID[] {r.getObject("id", UUID.class), r.getObject("owner_id", UUID.class)})
            .list();
    try {
      for (var photo : expired) discard(photo[1], photo[0]);
      // Recover a file left behind if its initial DB insert and best-effort removal both failed.
      try (var files = Files.list(directory)) {
        for (Path file :
            files.filter(p -> p.getFileName().toString().matches("[0-9a-f-]{36}\\.jpg")).toList()) {
          if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
              || !Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) continue;
          UUID id;
          try {
            id = UUID.fromString(file.getFileName().toString().substring(0, 36));
          } catch (IllegalArgumentException ignored) {
            continue;
          }
          if (db.sql("SELECT COUNT(*) FROM garden_photos WHERE id=:id")
                  .param("id", id)
                  .query(Integer.class)
                  .single()
              == 0) Files.deleteIfExists(file);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(
          "Unused photo cleanup could not finish. Check photo storage.", e);
    }
  }

  private Path path(UUID id) {
    return directory.resolve(id + ".jpg");
  }

  static byte[] sanitize(byte[] original) {
    if (original.length == 0 || original.length > MAX_BYTES)
      throw new IllegalArgumentException("Choose a JPG or PNG photo smaller than 5 MB.");
    try (var stream =
        new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(original))) {
      var readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext())
        throw new IllegalArgumentException("That isn't a readable JPG or PNG photo.");
      var reader = readers.next();
      try {
        if (!Set.of("JPEG", "JPG", "PNG").contains(reader.getFormatName().toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException("Choose a JPG or PNG photo.");
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 1
            || height < 1
            || width > 8192
            || height > 8192
            || (long) width * height > 20_000_000)
          throw new IllegalArgumentException("Choose a smaller photo (up to 20 megapixels).");
        BufferedImage image = reader.read(0);
        double scale = Math.min(1, 1600d / Math.max(width, height));
        BufferedImage clean =
            new BufferedImage(
                Math.max(1, (int) (width * scale)),
                Math.max(1, (int) (height * scale)),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = clean.createGraphics();
        try {
          graphics.setColor(Color.WHITE);
          graphics.fillRect(0, 0, clean.getWidth(), clean.getHeight());
          graphics.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
          graphics.drawImage(image, 0, 0, clean.getWidth(), clean.getHeight(), null);
        } finally {
          graphics.dispose();
        }
        int orientation = 1;
        try {
          var exif =
              ImageMetadataReader.readMetadata(new ByteArrayInputStream(original))
                  .getFirstDirectoryOfType(ExifIFD0Directory.class);
          if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION))
            orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
        } catch (Exception ignored) {
          /* Invalid optional metadata must not survive re-encoding. */
        }
        clean = orient(clean, orientation);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(clean, "JPEG", output);
        return output.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("That photo couldn't be read. Try another JPG or PNG.");
    }
  }

  private static BufferedImage orient(BufferedImage source, int orientation) {
    if (orientation < 2 || orientation > 8) return source;
    int w = source.getWidth(), h = source.getHeight();
    BufferedImage result =
        new BufferedImage(
            orientation >= 5 ? h : w, orientation >= 5 ? w : h, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int tx = x, ty = y;
        switch (orientation) {
          case 2 -> tx = w - 1 - x;
          case 3 -> {
            tx = w - 1 - x;
            ty = h - 1 - y;
          }
          case 4 -> ty = h - 1 - y;
          case 5 -> {
            tx = y;
            ty = x;
          }
          case 6 -> {
            tx = h - 1 - y;
            ty = x;
          }
          case 7 -> {
            tx = h - 1 - y;
            ty = w - 1 - x;
          }
          case 8 -> {
            tx = y;
            ty = w - 1 - x;
          }
        }
        result.setRGB(tx, ty, source.getRGB(x, y));
      }
    return result;
  }
}
