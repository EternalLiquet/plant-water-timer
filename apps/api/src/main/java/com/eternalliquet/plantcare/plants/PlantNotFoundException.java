package com.eternalliquet.plantcare.plants;

import java.util.UUID;

public final class PlantNotFoundException extends RuntimeException {

  public PlantNotFoundException(UUID plantId) {
    super("Plant not found: " + plantId);
  }
}
