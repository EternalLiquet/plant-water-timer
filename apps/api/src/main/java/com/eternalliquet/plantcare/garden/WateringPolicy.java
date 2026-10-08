package com.eternalliquet.plantcare.garden;

import java.time.LocalDate;
import java.util.Objects;

/** A user-adjustable check reminder, deliberately not species-specific watering advice. */
final class WateringPolicy {
  static final String VERSION = "watering-check-v1";

  static LocalDate nextCheck(LocalDate date, int days) {
    Objects.requireNonNull(date);
    if (days < 1 || days > 365)
      throw new IllegalArgumentException("Choose between 1 and 365 days.");
    return date.plusDays(days);
  }
}
