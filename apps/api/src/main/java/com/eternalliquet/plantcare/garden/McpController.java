package com.eternalliquet.plantcare.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A deliberately narrow, separately configured MCP JSON-RPC endpoint for one private garden. */
@RestController
class McpController {
  private final McpGardenService garden;
  private final ObjectMapper json;
  private final String bearerToken;

  McpController(
      McpGardenService garden,
      ObjectMapper json,
      @Value("${app.mcp.bearer-token:}") String bearerToken) {
    this.garden = garden;
    this.json = json;
    this.bearerToken = bearerToken;
  }

  @PostMapping(path = "/mcp", consumes = "application/json", produces = "application/json")
  ResponseEntity<?> handle(
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody JsonNode request) {
    if (bearerToken.isBlank()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    if (!validBearer(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    if (!request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())) {
      return ResponseEntity.ok(error(request.path("id"), -32600, "Invalid JSON-RPC request"));
    }
    JsonNode id = request.path("id");
    if ("notifications/initialized".equals(request.path("method").asText())) {
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok(switch (request.path("method").asText()) {
      case "initialize" -> result(
          id,
          Map.of(
              "protocolVersion", "2025-03-26",
              "capabilities", Map.of("tools", Map.of()),
              "serverInfo", Map.of("name", "private-plant-garden", "version", "0.1.0")));
      case "tools/list" -> result(id, Map.of("tools", garden.tools()));
      case "tools/call" -> result(id, garden.call(request.path("params")));
      default -> error(id, -32601, "Method not found");
    });
  }

  private boolean validBearer(String authorization) {
    String prefix = "Bearer ";
    if (authorization == null || !authorization.startsWith(prefix)) return false;
    return MessageDigest.isEqual(
        bearerToken.getBytes(StandardCharsets.UTF_8),
        authorization.substring(prefix.length()).getBytes(StandardCharsets.UTF_8));
  }

  private Map<String, Object> result(JsonNode id, Object value) {
    Map<String, Object> response = new java.util.LinkedHashMap<>();
    response.put("jsonrpc", "2.0");
    response.put("id", json.convertValue(id, Object.class));
    response.put("result", value);
    return response;
  }

  private Map<String, Object> error(JsonNode id, int code, String message) {
    Map<String, Object> response = new java.util.LinkedHashMap<>();
    response.put("jsonrpc", "2.0");
    response.put("id", json.convertValue(id, Object.class));
    response.put("error", Map.of("code", code, "message", message));
    return response;
  }
}
