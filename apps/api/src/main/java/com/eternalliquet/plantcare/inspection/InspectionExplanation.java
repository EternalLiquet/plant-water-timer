package com.eternalliquet.plantcare.inspection;

public final class InspectionExplanation {

  private InspectionExplanation() {}

  public static String forReason(InspectionReasonCode reasonCode) {
    return switch (reasonCode) {
      case SOIL_WET_DELAY_INSPECTION ->
          "The soil was recorded as wet. Allow more drying time and inspect the plant again "
              + "within the recommended window.";
      case SOIL_MOIST_USE_BASELINE ->
          "The soil was recorded as moist. Moisture remains, so inspect the plant again later "
              + "rather than watering based on a schedule.";
      case SOIL_DRY_INSPECT_SOONER ->
          "The soil was recorded as dry. Inspect the plant and confirm whether watering is "
              + "appropriate. Check it again within the recommended window.";
    };
  }
}
