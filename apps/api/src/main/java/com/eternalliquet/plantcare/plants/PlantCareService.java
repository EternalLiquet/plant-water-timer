package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.InspectionRecommendationPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlantCareService {

  private static final Duration ALLOWED_OBSERVATION_CLOCK_SKEW = Duration.ofMinutes(5);

  private final PlantJdbcRepository repository;
  private final InspectionRecommendationPolicy recommendationPolicy;
  private final IdentifierGenerator identifiers;
  private final Clock clock;

  PlantCareService(
      PlantJdbcRepository repository,
      InspectionRecommendationPolicy recommendationPolicy,
      IdentifierGenerator identifiers,
      Clock clock) {
    this.repository = repository;
    this.recommendationPolicy = recommendationPolicy;
    this.identifiers = identifiers;
    this.clock = clock;
  }

  @Transactional
  public PlantProfile createPlant(UUID ownerId, CreatePlantCommand command) {
    Objects.requireNonNull(ownerId, "ownerId is required");
    Objects.requireNonNull(command, "command is required");
    var plant =
        new PlantProfile(
            identifiers.next(),
            ownerId,
            command.displayName(),
            command.knownName(),
            command.environment(),
            command.potMaterial(),
            command.drainage(),
            command.lightLevel(),
            command.baselineInspectionIntervalDays(),
            Instant.now(clock));
    repository.save(plant);
    return plant;
  }

  @Transactional
  public ObservationRecommendation recordObservation(
      UUID ownerId, UUID plantId, RecordSoilObservationCommand command) {
    Objects.requireNonNull(ownerId, "ownerId is required");
    Objects.requireNonNull(plantId, "plantId is required");
    Objects.requireNonNull(command, "command is required");
    var plant = ownedPlant(ownerId, plantId);
    var createdAt = Instant.now(clock);
    validateObservationTimestamp(command.observedAt(), createdAt);
    var observation =
        new SoilObservation(
            identifiers.next(),
            plantId,
            ownerId,
            command.soilState(),
            command.notes(),
            command.observedAt(),
            createdAt);
    var generated =
        recommendationPolicy.recommend(
            command.soilState(),
            plant.baselineInspectionIntervalDays(),
            command.observedAt(),
            createdAt);
    var recommendation =
        new StoredInspectionRecommendation(
            identifiers.next(),
            plantId,
            observation.id(),
            ownerId,
            generated.recommendedInspectionAt(),
            generated.minimumInspectionAt(),
            generated.maximumInspectionAt(),
            generated.reasonCodes(),
            generated.explanation(),
            generated.rulesVersion(),
            generated.createdAt());

    repository.save(observation);
    repository.save(recommendation);
    return new ObservationRecommendation(observation, recommendation);
  }

  @Transactional(readOnly = true)
  public List<StoredInspectionRecommendation> recommendationHistory(UUID ownerId, UUID plantId) {
    Objects.requireNonNull(ownerId, "ownerId is required");
    Objects.requireNonNull(plantId, "plantId is required");
    ownedPlant(ownerId, plantId);
    return repository.findRecommendationHistory(ownerId, plantId);
  }

  private PlantProfile ownedPlant(UUID ownerId, UUID plantId) {
    return repository.findOwned(ownerId, plantId).orElseThrow(() -> new PlantNotFoundException(plantId));
  }

  private static void validateObservationTimestamp(Instant observedAt, Instant serverNow) {
    if (observedAt.isAfter(serverNow.plus(ALLOWED_OBSERVATION_CLOCK_SKEW))) {
      throw new IllegalArgumentException(
          "Observation timestamp cannot be more than five minutes in the future");
    }
  }
}
