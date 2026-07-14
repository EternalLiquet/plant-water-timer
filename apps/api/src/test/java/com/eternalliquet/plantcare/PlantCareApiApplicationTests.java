package com.eternalliquet.plantcare;

import static org.assertj.core.api.Assertions.assertThat;

import com.eternalliquet.plantcare.status.StatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PlantCareApiApplicationTests {

  @Autowired private StatusController statusController;

  @Test
  void contextLoads() {
    assertThat(statusController).isNotNull();
  }
}
