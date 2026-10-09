package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
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
