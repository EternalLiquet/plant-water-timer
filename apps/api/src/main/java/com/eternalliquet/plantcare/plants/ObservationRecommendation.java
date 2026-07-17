package com.eternalliquet.plantcare.plants;

public record ObservationRecommendation(
    SoilObservation observation, StoredInspectionRecommendation recommendation) {}
