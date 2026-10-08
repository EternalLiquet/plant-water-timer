package com.eternalliquet.plantcare.plants;

import java.time.Instant;
import java.util.UUID;

public record PlantProfile(
    UUID id,
    UUID ownerId,
    String displayName,
    String knownName,
    PlantEnvironment environment,
    PotMaterial potMaterial,
    Drainage drainage,
    LightLevel lightLevel,
    int baselineInspectionIntervalDays,
    Instant createdAt) {}
