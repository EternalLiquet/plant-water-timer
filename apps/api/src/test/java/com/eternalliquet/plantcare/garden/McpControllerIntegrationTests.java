package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:mcp-controller;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "app.password=test-password-only",
      "app.mcp.bearer-token=mcp-test-token",
      "app.mcp.owner-username=mcp-owner",
      "app.photo-directory=${java.io.tmpdir}/plant-mcp-test-photos"
    })
@AutoConfigureMockMvc
class McpControllerIntegrationTests {
  private static final UUID OWNER = GardenSecurity.ownerName("mcp-owner");
  private static final UUID OTHER_OWNER = GardenSecurity.ownerName("other-owner");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired GardenService garden;
  @Autowired JdbcClient db;

  @BeforeEach
  void resetDatabase() throws Exception {
    db.sql("DELETE FROM mcp_write_receipts").update();
    db.sql("DELETE FROM watering_events").update();
    db.sql("DELETE FROM garden_photos").update();
    db.sql("DELETE FROM plants").update();
    Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "plant-mcp-test-photos"));
  }

  @Test
  void listsAndGetsOnlyTheConfiguredOwnerPlants() throws Exception {
    var mine = create("Kitchen fern");
    createFor(OTHER_OWNER, "Other owner's aloe");

    var listed = call("list_plants", "{}");
    assertThat(listed.path("result").path("content").get(0).path("text").asText())
        .contains(mine.id().toString())
        .contains("Kitchen fern")
        .doesNotContain("Other owner's aloe");

    var fetched = call("get_plant", "{\"plantId\":\"" + mine.id() + "\"}");
    assertThat(fetched.path("result").path("content").get(0).path("text").asText())
        .contains("Kitchen fern");
  }

  @Test
  void returnsAnOwnedSavedPhotoAsMcpImageContentInsteadOfAUrl() throws Exception {
    var plant = create("Photo fern");
    var photoId = UUID.randomUUID();
    byte[] image = Base64.getDecoder().decode("/9j/2Q==");
    db.sql("INSERT INTO garden_photos(id,owner_id,created_at) VALUES(:id,:owner,CURRENT_TIMESTAMP)")
        .param("id", photoId)
        .param("owner", OWNER)
        .update();
    db.sql("UPDATE plants SET photo_id=:photo WHERE id=:plant AND owner_id=:owner")
        .param("photo", photoId)
        .param("plant", plant.id())
        .param("owner", OWNER)
        .update();
    Files.write(Path.of(System.getProperty("java.io.tmpdir"), "plant-mcp-test-photos", photoId + ".jpg"), image);

    var response = call("get_plant_photo", "{\"plantId\":\"" + plant.id() + "\"}");
    var content = response.path("result").path("content").get(0);
    assertThat(content.path("type").asText()).isEqualTo("image");
    assertThat(content.path("mimeType").asText()).isEqualTo("image/jpeg");
    assertThat(content.path("data").asText()).isEqualTo(Base64.getEncoder().encodeToString(image));
    assertThat(content.toString()).doesNotContain("/api/garden/photos/");
  }

  @Test
  void mcpRequiresBearerAndIgnoresBrowserAndSpoofedOwnerContext() throws Exception {
    var mine = create("Private fern");
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_plant\",\"arguments\":{\"plantId\":\"" + mine.id() + "\"}}}";
    mvc.perform(post("/mcp").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/mcp").with(user("gardener")).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post("/mcp").header("Authorization", "Bearer wrong").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    var response = mvc.perform(post("/mcp").header("Authorization", "Bearer mcp-test-token").header("X-Owner-Id", OTHER_OWNER).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertThat(response).contains(mine.id().toString()).doesNotContain("Other owner's aloe");
  }

  @Test
  void mcpRejectsForeignPlantAndRollsBackFailedWaterReceipt() throws Exception {
    var foreign = createFor(OTHER_OWNER, "Other owner's aloe");
    UUID request = UUID.randomUUID();
    var response = call("log_watering", waterArguments(foreign.id(), LocalDate.of(2026, 1, 1), request));
    assertThat(response.path("result").path("isError").asBoolean()).isTrue();
    assertThat(db.sql("SELECT COUNT(*) FROM mcp_write_receipts WHERE request_id=:id").param("id", request).query(Long.class).single()).isZero();
    assertThat(garden.history(OTHER_OWNER, foreign.id())).isEmpty();
  }

  @Test
  void mcpWaterReceiptSurvivesDailyNoOpAndUndo() throws Exception {
    var plant = create("Receipt fern");
    LocalDate date = LocalDate.of(2026, 1, 1);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    garden.water(OWNER, plant.id(), new GardenService.WaterRequest(first, date, "UTC"));

    String retryableNoOp = waterArguments(plant.id(), date, second);
    call("log_watering", retryableNoOp);
    garden.undo(OWNER, plant.id(), first);
    call("log_watering", retryableNoOp);

    assertThat(garden.history(OWNER, plant.id())).hasSize(1);
    assertThat(garden.history(OWNER, plant.id()).get(0).undone()).isTrue();
  }

  @Test
  void mcpWaterRejectsRequestIdReusedWithDifferentPayload() throws Exception {
    var plant = create("Conflict fern");
    UUID request = UUID.randomUUID();
    call("log_watering", waterArguments(plant.id(), LocalDate.of(2026, 1, 1), request));

    var response = call("log_watering", waterArguments(plant.id(), LocalDate.of(2026, 1, 2), request));

    assertThat(response.path("result").path("isError").asBoolean()).isTrue();
    assertThat(response.toString()).contains("different details");
  }

  @Test
  void concurrentDuplicateNoOpWaterRequestsCreateOneReceiptAndNoNewEvent() throws Exception {
    var plant = create("Concurrent fern");
    LocalDate date = LocalDate.of(2026, 1, 1);
    UUID original = UUID.randomUUID();
    UUID request = UUID.randomUUID();
    garden.water(OWNER, plant.id(), new GardenService.WaterRequest(original, date, "UTC"));
    String args = waterArguments(plant.id(), date, request);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var futures = executor.invokeAll(List.of((Callable<JsonNode>) () -> call("log_watering", args), () -> call("log_watering", args)));
      for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).path("result").path("isError").asBoolean()).isFalse();
    }
    assertThat(garden.history(OWNER, plant.id())).hasSize(1);
    assertThat(db.sql("SELECT COUNT(*) FROM mcp_write_receipts WHERE operation='log_watering' AND request_id=:id").param("id", request).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void confirmedNameCreateAndWaterWritesAreValidatedAndRetrySafe() throws Exception {
    var existing = create("Fern");
    var nameRequest = UUID.randomUUID();
    String renameArguments =
        "{\"plantId\":\""
            + existing.id()
            + "\",\"confirmedName\":\"Hall fern\",\"requestId\":\""
            + nameRequest
            + "\"}";
    call("confirm_plant_name", renameArguments);
    call("confirm_plant_name", renameArguments);
    assertThat(garden.get(OWNER, existing.id()).name()).isEqualTo("Hall fern");
    assertThat(db.sql("SELECT COUNT(*) FROM mcp_write_receipts").query(Long.class).single()).isEqualTo(1);

    var createRequest = UUID.randomUUID();
    String createArguments =
        "{\"name\":\"New aloe\",\"intervalDays\":7,\"zone\":\"UTC\",\"requestId\":\""
            + createRequest
            + "\"}";
    var created = call("create_plant", createArguments);
    var retried = call("create_plant", createArguments);
    String createdId = plantId(created);
    assertThat(plantId(retried)).isEqualTo(createdId);

    var waterRequest = UUID.randomUUID();
    String waterArguments =
        "{\"plantId\":\""
            + createdId
            + "\",\"date\":\"2026-01-01\",\"requestId\":\""
            + waterRequest
            + "\"}";
    call("log_watering", waterArguments);
    call("log_watering", waterArguments);
    assertThat(garden.history(OWNER, UUID.fromString(createdId))).hasSize(1);

    call("confirm_plant_name", "{\"plantId\":\"" + existing.id() + "\",\"confirmedName\":\" \",\"requestId\":\"" + UUID.randomUUID() + "\"}");
    assertThat(garden.get(OWNER, existing.id()).name()).isEqualTo("Hall fern");
  }

  private String waterArguments(UUID plantId, LocalDate date, UUID requestId) {
    return "{\"plantId\":\""
        + plantId
        + "\",\"date\":\""
        + date
        + "\",\"requestId\":\""
        + requestId
        + "\"}";
  }

  private String waterArguments(UUID plantId, LocalDate date) {
    return waterArguments(plantId, date, UUID.randomUUID());
  }

  private GardenService.Plant create(String name) {
    return createFor(OWNER, name);
  }

  private GardenService.Plant createFor(UUID owner, String name) {
    return garden.create(owner, new GardenService.Create(UUID.randomUUID(), name, null, null, null, 7, "UTC"));
  }

  private JsonNode call(String tool, String arguments) throws Exception {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + tool
            + "\",\"arguments\":"
            + arguments
            + "}}";
    var content =
        mvc.perform(
                post("/mcp")
                    .header("Authorization", "Bearer mcp-test-token")
                    .contentType("application/json")
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(content);
  }

  private String plantId(JsonNode response) throws Exception {
    return json.readTree(response.path("result").path("content").get(0).path("text").asText())
        .path("id")
        .asText();
  }
}
