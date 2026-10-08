package com.eternalliquet.plantcare.status;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatusController.class)
@TestPropertySource(properties = "app.version=test-version")
class StatusControllerTests {

  @Autowired private MockMvc mockMvc;

  @Test
  @WithMockUser
  void returnsApiStatus() throws Exception {
    mockMvc
        .perform(get("/api/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.service", equalTo("plant-care-api")))
        .andExpect(jsonPath("$.version", equalTo("test-version")));
  }
}
