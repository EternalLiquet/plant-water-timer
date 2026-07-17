package com.eternalliquet.plantcare.plants;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plant-controller-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
@AutoConfigureMockMvc
class PlantControllerIntegrationTests {

  private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_OWNER_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T12:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcClient jdbcClient;

  @BeforeEach
  void resetDatabase() {
    jdbcClient.sql("DELETE FROM inspection_recommendations").update();
    jdbcClient.sql("DELETE FROM soil_observations").update();
    jdbcClient.sql("DELETE FROM plants").update();
  }

  @Test
  void createPlantRequestCreatesAnOwnerScopedPlant() throws Exception {
    mockMvc
        .perform(
            post("/api/plants")
                .header("X-Owner-Id", OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPlantJson()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", containsString("/api/plants/")))
        .andExpect(jsonPath("$.id", not(nullValue())))
        .andExpect(jsonPath("$.ownerId", equalTo(OWNER_ID.toString())))
        .andExpect(jsonPath("$.displayName", equalTo("Kitchen fern")))
        .andExpect(jsonPath("$.environment", equalTo("INDOOR")))
        .andExpect(jsonPath("$.baselineInspectionIntervalDays", equalTo(4)));
  }

  @Test
  void invalidPlantRequestReturnsAValidationError() throws Exception {
    mockMvc
        .perform(
            post("/api/plants")
                .header("X-Owner-Id", OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    validPlantJson()
                        .replace("Kitchen fern", " ")
                        .replace("\"baselineInspectionIntervalDays\":4", "\"baselineInspectionIntervalDays\":0")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title", equalTo("Invalid request")));
  }

  @Test
  void invalidSoilStateReturnsAValidationErrorWithoutWriting() throws Exception {
    var plantId = createPlant();

    mockMvc
        .perform(
            post("/api/plants/{plantId}/observations", plantId)
                .header("X-Owner-Id", OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"soilState":"damp","observedAt":"2026-07-17T12:00:00Z"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title", equalTo("Invalid request")));

    var observationCount =
        jdbcClient.sql("SELECT COUNT(*) FROM soil_observations").query(Long.class).single();
    org.assertj.core.api.Assertions.assertThat(observationCount).isZero();
  }

  @Test
  void observationSubmissionReturnsTheGeneratedRecommendationAndExplanation() throws Exception {
    var plantId = createPlant();

    mockMvc
        .perform(
            post("/api/plants/{plantId}/observations", plantId)
                .header("X-Owner-Id", OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "soilState":"dry",
                      "notes":"Pot feels light",
                      "observedAt":"2026-07-17T12:00:00Z"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.observation.soilState", equalTo("DRY")))
        .andExpect(
            jsonPath(
                "$.recommendation.reasonCodes[0]", equalTo("SOIL_DRY_INSPECT_SOONER")))
        .andExpect(
            jsonPath(
                "$.recommendation.explanation",
                containsString("confirm whether watering is appropriate")))
        .andExpect(
            jsonPath("$.recommendation.rulesVersion", equalTo("inspection-rules-v1")))
        .andExpect(
            jsonPath(
                "$.recommendation.minimumInspectionAt", equalTo(OBSERVED_AT.toString())));
  }

  @Test
  void recommendationHistoryReturnsThePersistedRecommendation() throws Exception {
    var plantId = createPlant();
    createObservation(plantId);

    mockMvc
        .perform(
            get("/api/plants/{plantId}/recommendations", plantId)
                .header("X-Owner-Id", OWNER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(
            jsonPath("$[0].reasonCodes[0]", equalTo("SOIL_MOIST_USE_BASELINE")))
        .andExpect(jsonPath("$[0].explanation", containsString("Moisture remains")));
  }

  @Test
  void missingOwnerHeaderReturnsABadRequest() throws Exception {
    mockMvc
        .perform(post("/api/plants").contentType(MediaType.APPLICATION_JSON).content(validPlantJson()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title", equalTo("Invalid request")));
  }

  @Test
  void anotherOwnerReceivesNotFoundForRecommendationHistory() throws Exception {
    var plantId = createPlant();
    createObservation(plantId);

    mockMvc
        .perform(
            get("/api/plants/{plantId}/recommendations", plantId)
                .header("X-Owner-Id", OTHER_OWNER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title", equalTo("Plant not found")));
  }

  private UUID createPlant() throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/plants")
                    .header("X-Owner-Id", OWNER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validPlantJson()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).get("id").asText());
  }

  private void createObservation(UUID plantId) throws Exception {
    mockMvc
        .perform(
            post("/api/plants/{plantId}/observations", plantId)
                .header("X-Owner-Id", OWNER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"soilState":"moist","observedAt":"2026-07-17T12:00:00Z"}
                    """))
        .andExpect(status().isCreated());
  }

  private static String validPlantJson() {
    return """
        {
          "displayName":"Kitchen fern",
          "knownName":"Boston fern",
          "environment":"indoor",
          "potMaterial":"plastic",
          "drainage":"yes",
          "lightLevel":"bright_indirect",
          "baselineInspectionIntervalDays":4
        }
        """;
  }
}
