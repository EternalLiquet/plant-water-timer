package com.eternalliquet.plantcare.plants;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

record CreatePlantRequest(
    @NotBlank @Size(max = 100) String displayName,
    @Size(max = 100) String knownName,
    @NotNull PlantEnvironment environment,
    @NotNull PotMaterial potMaterial,
    @NotNull Drainage drainage,
    @NotNull LightLevel lightLevel,
    @Min(1) @Max(365) int baselineInspectionIntervalDays) {

  CreatePlantCommand toCommand() {
    return new CreatePlantCommand(
        displayName,
        knownName,
        environment,
        potMaterial,
        drainage,
        lightLevel,
        baselineInspectionIntervalDays);
  }
}
