package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.SoilState;
import java.time.Instant;
import java.util.UUID;

public record SoilObservation(
    UUID id,
    UUID plantId,
    UUID ownerId,
    SoilState soilState,
    String notes,
    Instant observedAt,
    Instant createdAt) {}
