package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.SoilState;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

record RecordSoilObservationRequest(
    @NotNull SoilState soilState, @Size(max = 1000) String notes, @NotNull Instant observedAt) {

  RecordSoilObservationCommand toCommand() {
    return new RecordSoilObservationCommand(soilState, notes, observedAt);
  }
}
