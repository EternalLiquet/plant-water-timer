package com.eternalliquet.plantcare.plants;

import java.util.Objects;

public record CreatePlantCommand(
    String displayName,
    String knownName,
    PlantEnvironment environment,
    PotMaterial potMaterial,
    Drainage drainage,
    LightLevel lightLevel,
    int baselineInspectionIntervalDays) {

  public CreatePlantCommand {
    displayName = requiredText(displayName, "Display name", 100);
    knownName = optionalText(knownName, "Known name", 100);
    Objects.requireNonNull(environment, "environment is required");
    Objects.requireNonNull(potMaterial, "potMaterial is required");
    Objects.requireNonNull(drainage, "drainage is required");
    Objects.requireNonNull(lightLevel, "lightLevel is required");
    if (baselineInspectionIntervalDays < 1 || baselineInspectionIntervalDays > 365) {
      throw new IllegalArgumentException(
          "Baseline inspection interval must be between 1 and 365 days");
    }
  }

  private static String requiredText(String value, String label, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    var trimmed = value.trim();
    if (trimmed.length() > maximumLength) {
      throw new IllegalArgumentException(label + " must be at most " + maximumLength + " characters");
    }
    return trimmed;
  }

  private static String optionalText(String value, String label, int maximumLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return requiredText(value, label, maximumLength);
  }
}
