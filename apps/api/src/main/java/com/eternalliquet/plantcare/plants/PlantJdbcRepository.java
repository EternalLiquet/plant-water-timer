package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.InspectionReasonCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PlantJdbcRepository {

  private final JdbcClient jdbcClient;

  PlantJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  void save(PlantProfile plant) {
    jdbcClient
        .sql(
            """
            INSERT INTO plants (
              id, owner_id, display_name, known_name, environment, pot_material, drainage,
              light_level, baseline_inspection_interval_days, created_at
            ) VALUES (
              :id, :ownerId, :displayName, :knownName, :environment, :potMaterial, :drainage,
              :lightLevel, :baselineInterval, :createdAt
            )
            """)
        .param("id", plant.id())
        .param("ownerId", plant.ownerId())
        .param("displayName", plant.displayName())
        .param("knownName", plant.knownName())
        .param("environment", plant.environment().name())
        .param("potMaterial", plant.potMaterial().name())
        .param("drainage", plant.drainage().name())
        .param("lightLevel", plant.lightLevel().name())
        .param("baselineInterval", plant.baselineInspectionIntervalDays())
        .param("createdAt", plant.createdAt())
        .update();
  }

  Optional<PlantProfile> findOwned(UUID ownerId, UUID plantId) {
    return jdbcClient
        .sql(
            """
            SELECT id, owner_id, display_name, known_name, environment, pot_material, drainage,
                   light_level, baseline_inspection_interval_days, created_at
            FROM plants
            WHERE id = :plantId AND owner_id = :ownerId
            """)
        .param("plantId", plantId)
        .param("ownerId", ownerId)
        .query(PlantJdbcRepository::mapPlant)
        .optional();
  }

  void save(SoilObservation observation) {
    jdbcClient
        .sql(
            """
            INSERT INTO soil_observations (
              id, plant_id, owner_id, soil_state, notes, observed_at, created_at
            ) VALUES (
              :id, :plantId, :ownerId, :soilState, :notes, :observedAt, :createdAt
            )
            """)
        .param("id", observation.id())
        .param("plantId", observation.plantId())
        .param("ownerId", observation.ownerId())
        .param("soilState", observation.soilState().name())
        .param("notes", observation.notes())
        .param("observedAt", observation.observedAt())
        .param("createdAt", observation.createdAt())
        .update();
  }

  void save(StoredInspectionRecommendation recommendation) {
    jdbcClient
        .sql(
            """
            INSERT INTO inspection_recommendations (
              id, plant_id, observation_id, owner_id, recommended_inspection_at,
              minimum_inspection_at, maximum_inspection_at, reason_code, explanation,
              rules_version, created_at
            ) VALUES (
              :id, :plantId, :observationId, :ownerId, :recommendedAt,
              :minimumAt, :maximumAt, :reasonCode, :explanation, :rulesVersion, :createdAt
            )
            """)
        .param("id", recommendation.id())
        .param("plantId", recommendation.plantId())
        .param("observationId", recommendation.observationId())
        .param("ownerId", recommendation.ownerId())
        .param("recommendedAt", recommendation.recommendedInspectionAt())
        .param("minimumAt", recommendation.minimumInspectionAt())
        .param("maximumAt", recommendation.maximumInspectionAt())
        .param("reasonCode", recommendation.reasonCodes().getFirst().name())
        .param("explanation", recommendation.explanation())
        .param("rulesVersion", recommendation.rulesVersion())
        .param("createdAt", recommendation.createdAt())
        .update();
  }

  List<StoredInspectionRecommendation> findRecommendationHistory(UUID ownerId, UUID plantId) {
    return jdbcClient
        .sql(
            """
            SELECT id, plant_id, observation_id, owner_id, recommended_inspection_at,
                   minimum_inspection_at, maximum_inspection_at, reason_code, explanation,
                   rules_version, created_at
            FROM inspection_recommendations
            WHERE plant_id = :plantId AND owner_id = :ownerId
            ORDER BY created_at DESC, id DESC
            """)
        .param("plantId", plantId)
        .param("ownerId", ownerId)
        .query(PlantJdbcRepository::mapRecommendation)
        .list();
  }

  private static PlantProfile mapPlant(ResultSet resultSet, int rowNumber) throws SQLException {
    return new PlantProfile(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("owner_id", UUID.class),
        resultSet.getString("display_name"),
        resultSet.getString("known_name"),
        PlantEnvironment.valueOf(resultSet.getString("environment")),
        PotMaterial.valueOf(resultSet.getString("pot_material")),
        Drainage.valueOf(resultSet.getString("drainage")),
        LightLevel.valueOf(resultSet.getString("light_level")),
        resultSet.getInt("baseline_inspection_interval_days"),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static StoredInspectionRecommendation mapRecommendation(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new StoredInspectionRecommendation(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("plant_id", UUID.class),
        resultSet.getObject("observation_id", UUID.class),
        resultSet.getObject("owner_id", UUID.class),
        resultSet.getObject("recommended_inspection_at", OffsetDateTime.class).toInstant(),
        resultSet.getObject("minimum_inspection_at", OffsetDateTime.class).toInstant(),
        resultSet.getObject("maximum_inspection_at", OffsetDateTime.class).toInstant(),
        List.of(InspectionReasonCode.valueOf(resultSet.getString("reason_code"))),
        resultSet.getString("explanation"),
        resultSet.getString("rules_version"),
        resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
