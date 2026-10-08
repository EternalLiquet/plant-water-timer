package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:photo-boundary;DB_CLOSE_DELAY=-1",
      "app.photo-directory=${java.io.tmpdir}/plant-private-boundary-photos"
    })
class IdentificationBoundaryTests {
  static HttpServer server;
  static String reply = "";
  static int status = 200;

  @DynamicPropertySource
  static void service(DynamicPropertyRegistry props) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/identify",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] data = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    server.start();
    props.add(
        "app.identify-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/identify");
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @Autowired PhotoService photos;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient db;
  @Autowired GardenService garden;

  byte[] image() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB), "PNG", out);
    return out.toByteArray();
  }

  @Test
  void uncertainProviderResultStaysUncertain() throws Exception {
    status = 200;
    reply =
        "{\"status\":\"unknown\",\"modelVersion\":\"test-v1\",\"candidates\":[{\"scientificName\":\"Monstera"
            + " deliciosa\",\"confidence\":0.31}]}";
    var result = photos.upload(UUID.randomUUID(), image());
    assertThat(result.status()).isEqualTo("unknown");
    assertThat(result.candidates()).hasSize(1);
  }

  @Test
  void unavailableModelKeepsPhotoAndManualFallback() throws Exception {
    status = 503;
    reply = "{}";
    UUID owner = UUID.randomUUID();
    var result = photos.upload(owner, image());
    assertThat(result.status()).isEqualTo("unavailable");
    assertThat(result.candidates()).isEmpty();
    assertThat(photos.read(owner, result.photoId())).isNotEmpty();
    assertThatThrownBy(() -> photos.read(UUID.randomUUID(), result.photoId()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void abandonedPhotosExpireAndDoNotPermanentlyFillTheGarden() throws Exception {
    UUID owner = UUID.randomUUID();
    for (int i = 0; i < 200; i++)
      db.sql("INSERT INTO garden_photos(id,owner_id,created_at) VALUES(:id,:owner,:time)")
          .param("id", UUID.randomUUID())
          .param("owner", owner)
          .param("time", java.time.Instant.now().minus(java.time.Duration.ofHours(25)))
          .update();
    status = 503;
    reply = "{}";
    assertThat(photos.upload(owner, image()).photoId()).isNotNull();
    assertThat(
            db.sql("SELECT COUNT(*) FROM garden_photos WHERE owner_id=:owner")
                .param("owner", owner)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void expiryNeverRemovesAReferencedPhoto() throws Exception {
    UUID owner = UUID.randomUUID();
    status = 503;
    reply = "{}";
    var uploaded = photos.upload(owner, image());
    garden.create(
        owner,
        new GardenService.Create(
            UUID.randomUUID(), "Fern", "", uploaded.photoId(), null, 7, "UTC"));
    db.sql("UPDATE garden_photos SET created_at=:time WHERE id=:id")
        .param("time", java.time.Instant.now().minus(java.time.Duration.ofHours(25)))
        .param("id", uploaded.photoId())
        .update();
    photos.prune();
    assertThat(photos.read(owner, uploaded.photoId())).isNotEmpty();
  }

  @Test
  void storedPhotoIsPrivateOnPosix() throws Exception {
    status = 503;
    reply = "{}";
    UUID owner = UUID.randomUUID();
    var result = photos.upload(owner, image());
    java.nio.file.Path path =
        java.nio.file.Path.of(
            System.getProperty("java.io.tmpdir"),
            "plant-private-boundary-photos",
            result.photoId() + ".jpg");
    if (java.nio.file.Files.getFileStore(path).supportsFileAttributeView("posix")) {
      assertThat(java.nio.file.Files.getPosixFilePermissions(path))
          .containsExactlyInAnyOrder(
              java.nio.file.attribute.PosixFilePermission.OWNER_READ,
              java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
  }

  @Test
  void concurrentAttachmentAndDiscardCannotLeaveBrokenPlantPhoto() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 8; i++) {
        UUID owner = UUID.randomUUID();
        status = 503;
        reply = "{}";
        var uploaded = photos.upload(owner, image());
        var start = new java.util.concurrent.CountDownLatch(1);
        var create =
            pool.submit(
                () -> {
                  start.await();
                  try {
                    return garden.create(
                        owner,
                        new GardenService.Create(
                            UUID.randomUUID(),
                            "Test plant",
                            "",
                            uploaded.photoId(),
                            null,
                            7,
                            "UTC"));
                  } catch (IllegalArgumentException expected) {
                    return null;
                  }
                });
        var discard =
            pool.submit(
                () -> {
                  start.await();
                  photos.discard(owner, uploaded.photoId());
                  return true;
                });
        start.countDown();
        var plant = create.get(10, java.util.concurrent.TimeUnit.SECONDS);
        discard.get(10, java.util.concurrent.TimeUnit.SECONDS);
        if (plant != null) assertThat(photos.read(owner, uploaded.photoId())).isNotEmpty();
        else assertThat(garden.list(owner)).isEmpty();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentCreateRetriesReturnOneSavedPlant() throws Exception {
    UUID owner = UUID.randomUUID();
    var request =
        new GardenService.Create(UUID.randomUUID(), "One plant", "", null, null, 7, "UTC");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var results = new java.util.ArrayList<java.util.concurrent.Future<GardenService.Plant>>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return garden.create(owner, request);
                }));
      start.countDown();
      var ids = new java.util.HashSet<UUID>();
      for (var result : results)
        ids.add(result.get(10, java.util.concurrent.TimeUnit.SECONDS).id());
      assertThat(ids).hasSize(1);
      assertThat(garden.list(owner)).hasSize(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void wateringKeepsThePlantsOriginalTimeZone() throws Exception {
    UUID owner = UUID.randomUUID();
    var plant =
        garden.create(
            owner,
            new GardenService.Create(
                UUID.randomUUID(), "Home fern", "", null, null, 7, "America/New_York"));
    UUID event = UUID.randomUUID();
    garden.water(
        owner,
        plant.id(),
        new GardenService.WaterRequest(event, java.time.LocalDate.of(2026, 1, 1), "Asia/Tokyo"));
    assertThat(
            db.sql("SELECT zone_id FROM watering_events WHERE id=:id")
                .param("id", event)
                .query(String.class)
                .single())
        .isEqualTo("America/New_York");
  }

  @Test
  void aChangedCreateRetryCannotSilentlyReturnOldDetails() {
    UUID owner = UUID.randomUUID(), request = UUID.randomUUID();
    garden.create(owner, new GardenService.Create(request, "Original", "", null, null, 7, "UTC"));
    assertThatThrownBy(
            () ->
                garden.create(
                    owner, new GardenService.Create(request, "Changed", "", null, null, 7, "UTC")))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("409");
  }
}
