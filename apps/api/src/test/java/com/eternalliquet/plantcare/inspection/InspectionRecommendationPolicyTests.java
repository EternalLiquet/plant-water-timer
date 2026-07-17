package com.eternalliquet.plantcare.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class InspectionRecommendationPolicyTests {

  private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T12:00:00Z");
  private static final Instant CREATED_AT = Instant.parse("2026-07-17T12:05:00Z");
  private final InspectionRecommendationPolicy policy = new InspectionRecommendationPolicy();

  @Test
  void wetSoilDelaysInspectionBeyondTheBaseline() {
    var recommendation = policy.recommend(SoilState.WET, 4, OBSERVED_AT, CREATED_AT);

    assertThat(recommendation.recommendedInspectionAt())
        .isAfter(OBSERVED_AT.plusSeconds(4L * 24 * 60 * 60));
    assertThat(recommendation.reasonCodes())
        .containsExactly(InspectionReasonCode.SOIL_WET_DELAY_INSPECTION);
  }

  @Test
  void moistSoilUsesTheBaselineWithAOneDayWindow() {
    var recommendation = policy.recommend(SoilState.MOIST, 4, OBSERVED_AT, CREATED_AT);

    assertThat(recommendation.minimumInspectionAt())
        .isEqualTo(Instant.parse("2026-07-20T12:00:00Z"));
    assertThat(recommendation.recommendedInspectionAt())
        .isEqualTo(Instant.parse("2026-07-21T12:00:00Z"));
    assertThat(recommendation.maximumInspectionAt())
        .isEqualTo(Instant.parse("2026-07-22T12:00:00Z"));
    assertThat(recommendation.reasonCodes())
        .containsExactly(InspectionReasonCode.SOIL_MOIST_USE_BASELINE);
  }

  @Test
  void drySoilProducesAnEarlierInspectionWithoutCommandingWatering() {
    var recommendation = policy.recommend(SoilState.DRY, 4, OBSERVED_AT, CREATED_AT);

    assertThat(recommendation.recommendedInspectionAt())
        .isBefore(OBSERVED_AT.plusSeconds(4L * 24 * 60 * 60));
    assertThat(recommendation.reasonCodes())
        .containsExactly(InspectionReasonCode.SOIL_DRY_INSPECT_SOONER);
    assertThat(recommendation.explanation())
        .contains("confirm whether watering is appropriate")
        .doesNotContain("Water now")
        .doesNotContain("Water every");
  }

  @Test
  void generatedDatesAreOrderedAndNeverPrecedeTheObservation() {
    for (SoilState soilState : SoilState.values()) {
      var recommendation = policy.recommend(soilState, 1, OBSERVED_AT, CREATED_AT);

      assertThat(recommendation.minimumInspectionAt()).isAfterOrEqualTo(OBSERVED_AT);
      assertThat(recommendation.recommendedInspectionAt())
          .isAfterOrEqualTo(recommendation.minimumInspectionAt());
      assertThat(recommendation.maximumInspectionAt())
          .isAfterOrEqualTo(recommendation.recommendedInspectionAt());
    }
  }

  @Test
  void invalidBaselineIntervalsAreRejected() {
    assertThatThrownBy(() -> policy.recommend(SoilState.MOIST, 0, OBSERVED_AT, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 365");
    assertThatThrownBy(() -> policy.recommend(SoilState.MOIST, 366, OBSERVED_AT, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 365");
  }

  @Test
  void recommendationsStoreTheStableRulesVersionAndExplicitCreationTime() {
    var recommendation = policy.recommend(SoilState.WET, 4, OBSERVED_AT, CREATED_AT);

    assertThat(recommendation.rulesVersion()).isEqualTo("inspection-rules-v1");
    assertThat(recommendation.createdAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void explanationsAreDerivedFromReasonCodes() {
    for (InspectionReasonCode reasonCode : InspectionReasonCode.values()) {
      assertThat(InspectionExplanation.forReason(reasonCode)).isNotBlank();
    }

    var recommendation = policy.recommend(SoilState.WET, 4, OBSERVED_AT, CREATED_AT);
    assertThat(recommendation.explanation())
        .isEqualTo(InspectionExplanation.forReason(recommendation.reasonCodes().getFirst()));
  }

  @Test
  void identicalInputsAlwaysProduceIdenticalRecommendations() {
    var first = policy.recommend(SoilState.DRY, 7, OBSERVED_AT, CREATED_AT);
    var second = policy.recommend(SoilState.DRY, 7, OBSERVED_AT, CREATED_AT);

    assertThat(first).isEqualTo(second);
  }
}
