package com.eternalliquet.plantcare.status;

import java.time.Instant;

public record StatusResponse(String service, String version, Instant timestamp) {}
