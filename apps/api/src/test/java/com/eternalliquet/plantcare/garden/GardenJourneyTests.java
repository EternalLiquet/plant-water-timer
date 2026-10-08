package com.eternalliquet.plantcare.garden;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:garden;DB_CLOSE_DELAY=-1",
      "app.password=test-password-only",
      "app.photo-directory=${java.io.tmpdir}/plant-private-test-photos"
    })
@AutoConfigureMockMvc
class GardenJourneyTests {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void strangersCannotReadPlants() throws Exception {
    mvc.perform(get("/api/garden/plants")).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "gardener")
  void csrfIsRequired() throws Exception {
    mvc.perform(post("/api/garden/plants").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "gardener")
  void createWaterRetryAndUndoKeepsOneHistoryEntry() throws Exception {
    String result =
        mvc.perform(
                post("/api/garden/plants")
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        """
                        {"name":"Kitchen fern","species":"","intervalDays":7,"lastWatered":"2026-01-01","zone":"UTC"}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.nextCheck").value("2026-01-08"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(result).get("id").asText();
    String event = UUID.randomUUID().toString();
    String payload = "{\"eventId\":\"" + event + "\",\"date\":\"2026-01-03\",\"zone\":\"UTC\"}";
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/garden/plants/" + id + "/water")
                  .with(csrf())
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.nextCheck").value("2026-01-10"));
    mvc.perform(get("/api/garden/plants/" + id + "/history"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
    mvc.perform(post("/api/garden/plants/" + id + "/water/" + event + "/undo").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCheck").value("2026-01-08"));
  }

  @Test
  @WithMockUser(username = "gardener")
  void invalidDatesAndNamesAreRejected() throws Exception {
    mvc.perform(
            post("/api/garden/plants")
                .with(csrf())
                .contentType("application/json")
                .content("{\"name\":\" \",\"intervalDays\":0,\"zone\":\"UTC\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(username = "gardener")
  void oldApiCannotImpersonateOwner() throws Exception {
    mvc.perform(
            get("/api/plants/00000000-0000-0000-0000-000000000001/recommendations")
                .header("X-Owner-Id", "00000000-0000-0000-0000-000000000002"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "owner-a")
  void anotherOwnerCannotReadOrWaterPlant() throws Exception {
    String result =
        mvc.perform(
                post("/api/garden/plants")
                    .with(csrf())
                    .contentType("application/json")
                    .content("{\"name\":\"Aloe\",\"intervalDays\":7,\"zone\":\"UTC\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(result).get("id").asText();
    mvc.perform(get("/api/garden/plants/" + id + "/history").with(user("owner-b")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/garden/plants/" + id + "/water")
                .with(user("owner-b"))
                .with(csrf())
                .contentType("application/json")
                .content(
                    "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"date\":\"2026-01-01\",\"zone\":\"UTC\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(username = "gardener")
  void malformedPhotoIsRejectedBeforeInference() throws Exception {
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "x.png", "image/png", "not an image".getBytes());
    mvc.perform(multipart("/api/garden/photos").file(file).with(csrf()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void sessionHasCsrfButDoesNotAuthenticateStranger() throws Exception {
    mvc.perform(get("/api/session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.csrfToken").isString());
  }

  @Test
  void realPasswordCreatesAnAuthenticatedSession() throws Exception {
    var response =
        mvc.perform(
                post("/login")
                    .with(csrf())
                    .param("username", "gardener")
                    .param("password", "test-password-only"))
            .andExpect(status().is3xxRedirection())
            .andReturn();
    mvc.perform(
            get("/api/session")
                .session(
                    (org.springframework.mock.web.MockHttpSession)
                        response.getRequest().getSession(false)))
        .andExpect(jsonPath("$.authenticated").value(true));
  }

  @Test
  @WithMockUser(username = "gardener")
  void legacyWritesCannotUseOwnerHeaderEvenWithCsrf() throws Exception {
    for (String path :
        java.util.List.of(
            "/api/plants", "/api/plants/00000000-0000-0000-0000-000000000001/observations"))
      mvc.perform(
              post(path)
                  .with(csrf())
                  .header("X-Owner-Id", UUID.randomUUID())
                  .contentType("application/json")
                  .content("{}"))
          .andExpect(status().isForbidden());
  }
}
