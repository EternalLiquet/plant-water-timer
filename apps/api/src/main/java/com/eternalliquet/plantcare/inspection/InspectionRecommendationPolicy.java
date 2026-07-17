package com.eternalliquet.plantcare.inspection;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public final class InspectionRecommendationPolicy {

  public static final String RULES_VERSION = "inspection-rules-v1";

  public InspectionRecommendation recommend(
      SoilState soilState, int baselineIntervalDays, Instant observedAt, Instant createdAt) {
    Objects.requireNonNull(soilState, "soilState is required");
    Objects.requireNonNull(observedAt, "observedAt is required");
    Objects.requireNonNull(createdAt, "createdAt is required");
    validateBaseline(baselineIntervalDays);

    var window = windowFor(soilState, baselineIntervalDays);
    var reasonCode = reasonFor(soilState);
    return new InspectionRecommendation(
        observedAt.plus(window.recommendedDays(), ChronoUnit.DAYS),
        observedAt.plus(window.minimumDays(), ChronoUnit.DAYS),
        observedAt.plus(window.maximumDays(), ChronoUnit.DAYS),
        List.of(reasonCode),
        InspectionExplanation.forReason(reasonCode),
        RULES_VERSION,
        createdAt);
  }

  private static void validateBaseline(int baselineIntervalDays) {
    if (baselineIntervalDays < 1 || baselineIntervalDays > 365) {
      throw new IllegalArgumentException("Baseline inspection interval must be between 1 and 365 days");
    }
  }

  private static InspectionWindow windowFor(SoilState soilState, int baselineIntervalDays) {
    return switch (soilState) {
      case WET ->
          new InspectionWindow(
              baselineIntervalDays, baselineIntervalDays + 1, baselineIntervalDays + 2);
      case MOIST ->
          new InspectionWindow(
              Math.max(0, baselineIntervalDays - 1),
              baselineIntervalDays,
              baselineIntervalDays + 1);
      case DRY -> new InspectionWindow(0, baselineIntervalDays / 2, baselineIntervalDays);
    };
  }

  private static InspectionReasonCode reasonFor(SoilState soilState) {
    return switch (soilState) {
      case WET -> InspectionReasonCode.SOIL_WET_DELAY_INSPECTION;
      case MOIST -> InspectionReasonCode.SOIL_MOIST_USE_BASELINE;
      case DRY -> InspectionReasonCode.SOIL_DRY_INSPECT_SOONER;
    };
  }

  private record InspectionWindow(long minimumDays, long recommendedDays, long maximumDays) {}
}
