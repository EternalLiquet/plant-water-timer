package com.eternalliquet.plantcare.status;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/status")
public class StatusController {

  private final String version;

  public StatusController(@Value("${app.version}") String version) {
    this.version = version;
  }

  @GetMapping
  public StatusResponse status() {
    return new StatusResponse("plant-care-api", version, Instant.now());
  }
}
