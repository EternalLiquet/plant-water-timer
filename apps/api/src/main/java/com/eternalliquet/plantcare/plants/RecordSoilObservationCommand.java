package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.SoilState;
import java.time.Instant;
import java.util.Objects;

public record RecordSoilObservationCommand(SoilState soilState, String notes, Instant observedAt) {

  public RecordSoilObservationCommand {
    Objects.requireNonNull(soilState, "soilState is required");
    Objects.requireNonNull(observedAt, "observedAt is required");
    if (notes != null) {
      notes = notes.trim();
      if (notes.isEmpty()) {
        notes = null;
      } else if (notes.length() > 1000) {
        throw new IllegalArgumentException("Observation notes must be at most 1000 characters");
      }
    }
  }
}
