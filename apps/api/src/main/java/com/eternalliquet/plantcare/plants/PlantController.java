package com.eternalliquet.plantcare.plants;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plants")
class PlantController {

  private final PlantCareService plantCareService;

  PlantController(PlantCareService plantCareService) {
    this.plantCareService = plantCareService;
  }

  @PostMapping
  ResponseEntity<PlantProfile> createPlant(
      @RequestHeader("X-Owner-Id") UUID ownerId, @Valid @RequestBody CreatePlantRequest request) {
    var plant = plantCareService.createPlant(ownerId, request.toCommand());
    return ResponseEntity.created(URI.create("/api/plants/" + plant.id())).body(plant);
  }

  @PostMapping("/{plantId}/observations")
  ResponseEntity<ObservationRecommendation> recordObservation(
      @RequestHeader("X-Owner-Id") UUID ownerId,
      @PathVariable UUID plantId,
      @Valid @RequestBody RecordSoilObservationRequest request) {
    var result = plantCareService.recordObservation(ownerId, plantId, request.toCommand());
    return ResponseEntity.created(
            URI.create("/api/plants/" + plantId + "/recommendations/" + result.recommendation().id()))
        .body(result);
  }

  @GetMapping("/{plantId}/recommendations")
  List<StoredInspectionRecommendation> recommendationHistory(
      @RequestHeader("X-Owner-Id") UUID ownerId, @PathVariable UUID plantId) {
    return plantCareService.recommendationHistory(ownerId, plantId);
  }
}
