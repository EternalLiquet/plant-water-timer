package com.eternalliquet.plantcare.plants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eternalliquet.plantcare.inspection.InspectionReasonCode;
import com.eternalliquet.plantcare.inspection.SoilState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:plant-care-service-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.clean-disabled=false"
    })
class PlantCareServiceIntegrationTests {

  private static final UUID OWNER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_OWNER_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID PLANT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID OBSERVATION_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");
  private static final UUID RECOMMENDATION_ID =
      UUID.fromString("50000000-0000-0000-0000-000000000005");
  private static final Instant NOW = Instant.parse("2026-07-17T12:05:00Z");
  private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T12:00:00Z");

  @Autowired private PlantCareService plantCareService;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private TestIdentifierGenerator identifiers;

  @BeforeEach
  void resetDatabaseAndIdentifiers() {
    jdbcClient.sql("DELETE FROM inspection_recommendations").update();
    jdbcClient.sql("DELETE FROM soil_observations").update();
    jdbcClient.sql("DELETE FROM plants").update();
    identifiers.reset();
  }

  @Test
  void createsAPlantForTheOwnerWithValidInput() {
    identifiers.willReturn(PLANT_ID);

    var plant = plantCareService.createPlant(OWNER_ID, validPlantCommand());

    assertThat(plant.id()).isEqualTo(PLANT_ID);
    assertThat(plant.ownerId()).isEqualTo(OWNER_ID);
    assertThat(plant.displayName()).isEqualTo("Kitchen fern");
    assertThat(plant.baselineInspectionIntervalDays()).isEqualTo(4);
    assertThat(plant.createdAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsInvalidPlantInputBeforeWriting() {
    assertThatThrownBy(
            () ->
                plantCareService.createPlant(
                    OWNER_ID,
                    new CreatePlantCommand(
                        " ",
                        null,
                        PlantEnvironment.INDOOR,
                        PotMaterial.PLASTIC,
                        Drainage.YES,
                        LightLevel.BRIGHT_INDIRECT,
                        0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(rowCount("plants")).isZero();
  }

  @Test
  void recordingObservationsAppendsHistoryInsteadOfOverwritingIt() {
    createPlant();
    identifiers.willReturn(
        OBSERVATION_ID,
        RECOMMENDATION_ID,
        UUID.fromString("60000000-0000-0000-0000-000000000006"),
        UUID.fromString("70000000-0000-0000-0000-000000000007"));

    plantCareService.recordObservation(
        OWNER_ID, PLANT_ID, new RecordSoilObservationCommand(SoilState.MOIST, "Topsoil cool", OBSERVED_AT));
    plantCareService.recordObservation(
        OWNER_ID,
        PLANT_ID,
        new RecordSoilObservationCommand(SoilState.DRY, "Pot feels light", OBSERVED_AT.plusSeconds(60)));

    assertThat(rowCount("soil_observations")).isEqualTo(2);
  }

  @Test
  void recordingAnObservationGeneratesAndPersistsARecommendation() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);

    var result =
        plantCareService.recordObservation(
            OWNER_ID,
            PLANT_ID,
            new RecordSoilObservationCommand(SoilState.DRY, "Pot feels light", OBSERVED_AT));

    assertThat(result.observation().id()).isEqualTo(OBSERVATION_ID);
    assertThat(result.recommendation().id()).isEqualTo(RECOMMENDATION_ID);
    assertThat(result.recommendation().reasonCodes())
        .containsExactly(InspectionReasonCode.SOIL_DRY_INSPECT_SOONER);
    assertThat(result.recommendation().rulesVersion()).isEqualTo("inspection-rules-v1");
    assertThat(result.recommendation().explanation())
        .contains("confirm whether watering is appropriate");
    assertThat(rowCount("inspection_recommendations")).isOne();
  }

  @Test
  void observationAndRecommendationWritesAreAtomic() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);
    plantCareService.recordObservation(
        OWNER_ID, PLANT_ID, new RecordSoilObservationCommand(SoilState.MOIST, null, OBSERVED_AT));

    var secondObservationId = UUID.fromString("60000000-0000-0000-0000-000000000006");
    identifiers.willReturn(secondObservationId, RECOMMENDATION_ID);

    assertThatThrownBy(
            () ->
                plantCareService.recordObservation(
                    OWNER_ID,
                    PLANT_ID,
                    new RecordSoilObservationCommand(
                        SoilState.WET, null, OBSERVED_AT.plusSeconds(60))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(rowCount("soil_observations")).isOne();
    assertThat(rowCount("inspection_recommendations")).isOne();
  }

  @Test
  void anotherOwnerCannotAddAnObservationToThePlant() {
    createPlant();

    assertThatThrownBy(
            () ->
                plantCareService.recordObservation(
                    OTHER_OWNER_ID,
                    PLANT_ID,
                    new RecordSoilObservationCommand(SoilState.WET, null, OBSERVED_AT)))
        .isInstanceOf(PlantNotFoundException.class);
    assertThat(rowCount("soil_observations")).isZero();
  }

  @Test
  void anotherOwnerCannotRetrieveRecommendationHistory() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);
    plantCareService.recordObservation(
        OWNER_ID, PLANT_ID, new RecordSoilObservationCommand(SoilState.MOIST, null, OBSERVED_AT));

    assertThatThrownBy(() -> plantCareService.recommendationHistory(OTHER_OWNER_ID, PLANT_ID))
        .isInstanceOf(PlantNotFoundException.class);
    assertThat(plantCareService.recommendationHistory(OWNER_ID, PLANT_ID)).hasSize(1);
  }

  @Test
  void observationBeyondAllowedClockSkewIsRejectedWithoutPersistingHistory() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);

    assertThatThrownBy(
            () ->
                plantCareService.recordObservation(
                    OWNER_ID,
                    PLANT_ID,
                    new RecordSoilObservationCommand(
                        SoilState.MOIST,
                        null,
                        NOW.plus(Duration.ofMinutes(5)).plusNanos(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("future");
    assertThat(rowCount("soil_observations")).isZero();
    assertThat(rowCount("inspection_recommendations")).isZero();
  }

  @Test
  void observationExactlyAtAllowedClockSkewIsAcceptedUsingTheInjectedClock() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);

    plantCareService.recordObservation(
        OWNER_ID,
        PLANT_ID,
        new RecordSoilObservationCommand(
            SoilState.MOIST, null, NOW.plus(Duration.ofMinutes(5))));

    assertThat(rowCount("soil_observations")).isOne();
    assertThat(rowCount("inspection_recommendations")).isOne();
  }

  @Test
  void observationWithinAllowedClockSkewIsAccepted() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);

    plantCareService.recordObservation(
        OWNER_ID,
        PLANT_ID,
        new RecordSoilObservationCommand(
            SoilState.DRY, null, NOW.plus(Duration.ofMinutes(4)).plusSeconds(59)));

    assertThat(rowCount("soil_observations")).isOne();
  }

  @Test
  void historicalObservationIsAccepted() {
    createPlant();
    identifiers.willReturn(OBSERVATION_ID, RECOMMENDATION_ID);

    plantCareService.recordObservation(
        OWNER_ID,
        PLANT_ID,
        new RecordSoilObservationCommand(
            SoilState.WET, null, NOW.minus(Duration.ofDays(365))));

    assertThat(rowCount("soil_observations")).isOne();
  }

  private PlantProfile createPlant() {
    identifiers.willReturn(PLANT_ID);
    return plantCareService.createPlant(OWNER_ID, validPlantCommand());
  }

  private static CreatePlantCommand validPlantCommand() {
    return new CreatePlantCommand(
        "Kitchen fern",
        "Boston fern",
        PlantEnvironment.INDOOR,
        PotMaterial.PLASTIC,
        Drainage.YES,
        LightLevel.BRIGHT_INDIRECT,
        4);
  }

  private long rowCount(String table) {
    return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
  }

  @TestConfiguration
  static class DeterministicTestConfiguration {

    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    TestIdentifierGenerator testIdentifierGenerator() {
      return new TestIdentifierGenerator();
    }
  }

  static final class TestIdentifierGenerator implements IdentifierGenerator {
    private final ArrayDeque<UUID> values = new ArrayDeque<>();

    void willReturn(UUID... identifiers) {
      values.addAll(Arrays.asList(identifiers));
    }

    void reset() {
      values.clear();
    }

    @Override
    public UUID next() {
      return values.remove();
    }
  }
}
