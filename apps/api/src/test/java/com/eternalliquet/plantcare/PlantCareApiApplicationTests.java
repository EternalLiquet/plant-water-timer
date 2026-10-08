package com.eternalliquet.plantcare;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternalliquet.plantcare.status.StatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:application-context-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
      "spring.datasource.username=sa",
      "spring.datasource.password="
    })
class PlantCareApiApplicationTests {

  @Autowired private StatusController statusController;

  @Test
  void contextLoads() {
    assertThat(statusController).isNotNull();
  }
}
