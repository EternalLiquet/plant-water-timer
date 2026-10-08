package com.eternalliquet.plantcare.plants;

import com.eternalliquet.plantcare.inspection.InspectionRecommendationPolicy;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PlantCareConfiguration {

  @Bean
  Clock utcClock() {
    return Clock.systemUTC();
  }

  @Bean
  IdentifierGenerator identifierGenerator() {
    return UUID::randomUUID;
  }

  @Bean
  InspectionRecommendationPolicy inspectionRecommendationPolicy() {
    return new InspectionRecommendationPolicy();
  }
}
