package com.eternalliquet.plantcare.inspection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record InspectionRecommendation(
    Instant recommendedInspectionAt,
    Instant minimumInspectionAt,
    Instant maximumInspectionAt,
    List<InspectionReasonCode> reasonCodes,
    String explanation,
    String rulesVersion,
    Instant createdAt) {

  public InspectionRecommendation {
    Objects.requireNonNull(recommendedInspectionAt, "recommendedInspectionAt is required");
    Objects.requireNonNull(minimumInspectionAt, "minimumInspectionAt is required");
    Objects.requireNonNull(maximumInspectionAt, "maximumInspectionAt is required");
    reasonCodes = List.copyOf(Objects.requireNonNull(reasonCodes, "reasonCodes are required"));
    Objects.requireNonNull(explanation, "explanation is required");
    Objects.requireNonNull(rulesVersion, "rulesVersion is required");
    Objects.requireNonNull(createdAt, "createdAt is required");

    if (reasonCodes.isEmpty()) {
      throw new IllegalArgumentException("At least one reason code is required");
    }
    if (minimumInspectionAt.isAfter(recommendedInspectionAt)
        || recommendedInspectionAt.isAfter(maximumInspectionAt)) {
      throw new IllegalArgumentException(
          "Inspection dates must be ordered minimum, recommended, maximum");
    }
  }
}
