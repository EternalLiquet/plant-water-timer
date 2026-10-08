package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.InspectionReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoredInspectionRecommendation(
    UUID id,
    UUID plantId,
    UUID observationId,
    UUID ownerId,
    Instant recommendedInspectionAt,
    Instant minimumInspectionAt,
    Instant maximumInspectionAt,
    List<InspectionReasonCode> reasonCodes,
    String explanation,
    String rulesVersion,
    Instant createdAt) {}
