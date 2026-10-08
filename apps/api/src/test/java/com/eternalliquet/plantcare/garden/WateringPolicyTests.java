package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WateringPolicyTests {
  @Test
  void addsCalendarDaysWithoutWateringCommands() {
    assertThat(WateringPolicy.nextCheck(LocalDate.parse("2026-03-07"), 7))
        .isEqualTo(LocalDate.parse("2026-03-14"));
  }

  @Test
  void rejectsUnsafeIntervals() {
    assertThatThrownBy(() -> WateringPolicy.nextCheck(LocalDate.now(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> WateringPolicy.nextCheck(LocalDate.now(), 366))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
