package com.eternalliquet.plantcare.garden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class McpGardenService {
  private final GardenService garden;
  private final PhotoService photos;
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final UUID owner;
  private final Object[] nameLocks =
      java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
  private final Object[] waterLocks =
      java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

  McpGardenService(
      GardenService garden,
      PhotoService photos,
      JdbcClient db,
      ObjectMapper json,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.mcp.owner-username:gardener}") String ownerUsername) {
    if (ownerUsername.isBlank()) throw new IllegalArgumentException("app.mcp.owner-username is required");
    this.garden = garden;
    this.photos = photos;
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
    this.owner = GardenSecurity.ownerName(ownerUsername);
  }

  List<Map<String, Object>> tools() {
    return List.of(
        tool("list_plants", "List the authenticated private garden's plants.", objectSchema(Map.of())),
        tool(
            "get_plant",
            "Get one plant in the authenticated private garden.",
            objectSchema(Map.of("plantId", uuid("Plant UUID")), List.of("plantId"))),
        tool(
            "get_plant_photo",
            "Return an authorized saved plant photo as MCP image content, never a URL.",
            objectSchema(Map.of("plantId", uuid("Plant UUID")), List.of("plantId"))),
        tool(
            "confirm_plant_name",
            "Save a user-confirmed display name. requestId is mandatory and safe to retry only with identical values.",
            objectSchema(
                Map.of(
                    "plantId", uuid("Plant UUID"),
                    "confirmedName", string("Confirmed plant name", 100),
                    "requestId", uuid("Idempotency UUID")),
                List.of("plantId", "confirmedName", "requestId"))),
        tool(
            "create_plant",
            "Create a plant after the user confirms its details. requestId is mandatory and safe to retry only with identical values.",
            objectSchema(
                Map.of(
                    "name", string("Plant display name", 100),
                    "species", string("Optional plant type", 100),
                    "intervalDays", Map.of("type", "integer", "minimum", 1, "maximum", 365),
                    "zone", string("IANA timezone, for example UTC", 80),
                    "requestId", uuid("Idempotency UUID")),
                List.of("name", "intervalDays", "requestId"))),
        tool(
            "log_watering",
            "Append a confirmed watering event. requestId is mandatory and safe to retry only with identical values.",
            objectSchema(
                Map.of(
                    "plantId", uuid("Plant UUID"),
                    "date", Map.of("type", "string", "format", "date"),
                    "requestId", uuid("Idempotency UUID")),
                List.of("plantId", "date", "requestId"))));
  }

  Map<String, Object> call(JsonNode params) {
    if (!params.isObject()) return failure("tools/call requires an object params value.");
    try {
      String name = requiredText(params, "name");
      JsonNode arguments = params.path("arguments");
      if (!arguments.isObject()) throw new IllegalArgumentException("arguments must be an object.");
      return switch (name) {
        case "list_plants" -> text(json.writeValueAsString(garden.list(owner).stream().map(this::plant).toList()));
        case "get_plant" -> text(json.writeValueAsString(plant(garden.get(owner, uuid(arguments, "plantId")))));
        case "get_plant_photo" -> photo(arguments);
        case "confirm_plant_name" -> text(json.writeValueAsString(plant(confirmName(arguments))));
        case "create_plant" -> text(json.writeValueAsString(plant(create(arguments))));
        case "log_watering" -> text(json.writeValueAsString(plant(water(arguments))));
        default -> failure("Unknown plant tool.");
      };
    } catch (ResponseStatusException ex) {
      return failure(ex.getReason() == null ? ex.getMessage() : ex.getReason());
    } catch (IllegalArgumentException ex) {
      return failure(ex.getMessage());
    } catch (IOException ex) {
      return failure("The saved photo could not be read.");
    }
  }

  private Map<String, Object> photo(JsonNode args) throws IOException {
    var plant = garden.get(owner, uuid(args, "plantId"));
    if (plant.photoId() == null) throw new IllegalArgumentException("This plant has no saved photo.");
    return Map.of(
        "content",
        List.of(
            Map.of(
                "type", "image",
                "mimeType", "image/jpeg",
                "data", Base64.getEncoder().encodeToString(photos.read(owner, plant.photoId())))));
  }

  private GardenService.Plant create(JsonNode args) {
    UUID requestId = uuid(args, "requestId");
    String name = requiredText(args, "name");
    String species = optionalText(args, "species");
    int interval = requiredInt(args, "intervalDays");
    String zone = optionalText(args, "zone");
    return garden.create(owner, new GardenService.Create(requestId, name, species, null, null, interval, zone));
  }

  private GardenService.Plant water(JsonNode args) {
    UUID plantId = uuid(args, "plantId");
    UUID requestId = uuid(args, "requestId");
    LocalDate date;
    try {
      date = LocalDate.parse(requiredText(args, "date"));
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new IllegalArgumentException("date must be an ISO-8601 calendar date.");
    }
    String fingerprint = waterFingerprint(plantId, date);
    Object lock = waterLocks[Math.floorMod(java.util.Objects.hash(owner, requestId), waterLocks.length)];
    synchronized (lock) {
      return transactions.execute(
          status -> {
            var receipt =
                db.sql(
                        "SELECT plant_id,fingerprint FROM mcp_write_receipts WHERE owner_id=:owner"
                            + " AND operation='log_watering' AND request_id=:request FOR UPDATE")
                    .param("owner", owner)
                    .param("request", requestId)
                    .query((r, n) -> List.of(r.getObject("plant_id", UUID.class), r.getString("fingerprint")))
                    .optional();
            if (receipt.isPresent()) {
              if (!receipt.get().equals(List.of(plantId, fingerprint))) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This requestId was already used with different details.");
              }
              return garden.get(owner, plantId);
            }
            var plant = garden.water(owner, plantId, new GardenService.WaterRequest(requestId, date, null));
            db.sql(
                    "INSERT INTO mcp_write_receipts(owner_id,operation,request_id,plant_id,fingerprint,created_at)"
                        + " VALUES(:owner,'log_watering',:request,:plant,:fingerprint,:created)")
                .param("owner", owner)
                .param("request", requestId)
                .param("plant", plantId)
                .param("fingerprint", fingerprint)
                .param("created", clock.instant())
                .update();
            return plant;
          });
    }
  }

  private GardenService.Plant confirmName(JsonNode args) {
    UUID plantId = uuid(args, "plantId");
    UUID requestId = uuid(args, "requestId");
    String name = requiredText(args, "confirmedName");
    validateName(name);
    String fingerprint = fingerprint(plantId, name);
    Object lock = nameLocks[Math.floorMod(java.util.Objects.hash(owner, requestId), nameLocks.length)];
    synchronized (lock) {
      return transactions.execute(
          status -> {
            var receipt =
                db.sql(
                        "SELECT plant_id,fingerprint FROM mcp_write_receipts WHERE owner_id=:owner"
                            + " AND operation='confirm_plant_name' AND request_id=:request FOR UPDATE")
                    .param("owner", owner)
                    .param("request", requestId)
                    .query((r, n) -> List.of(r.getObject("plant_id", UUID.class), r.getString("fingerprint")))
                    .optional();
            if (receipt.isPresent()) {
              if (!receipt.get().equals(List.of(plantId, fingerprint))) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This requestId was already used with different details.");
              }
              return garden.get(owner, plantId);
            }
            int updated =
                db.sql("UPDATE plants SET display_name=:name WHERE id=:id AND owner_id=:owner")
                    .param("name", name.strip())
                    .param("id", plantId)
                    .param("owner", owner)
                    .update();
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plant not found.");
            db.sql(
                    "INSERT INTO mcp_write_receipts(owner_id,operation,request_id,plant_id,fingerprint,created_at)"
                        + " VALUES(:owner,'confirm_plant_name',:request,:plant,:fingerprint,:created)")
                .param("owner", owner)
                .param("request", requestId)
                .param("plant", plantId)
                .param("fingerprint", fingerprint)
                .param("created", clock.instant())
                .update();
            return garden.get(owner, plantId);
          });
    }
  }

  private Map<String, Object> plant(GardenService.Plant plant) {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("id", plant.id());
    result.put("name", plant.name());
    result.put("species", plant.species());
    result.put("photoAvailable", plant.photoId() != null);
    result.put("lastWatered", plant.lastWatered());
    result.put("nextCheck", plant.nextCheck());
    result.put("intervalDays", plant.intervalDays());
    return result;
  }

  private static Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
    return Map.of("name", name, "description", description, "inputSchema", schema);
  }

  private static Map<String, Object> objectSchema(Map<String, Object> properties) {
    return objectSchema(properties, List.of());
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
  }

  private static Map<String, Object> uuid(String description) {
    return Map.of("type", "string", "format", "uuid", "description", description);
  }

  private static Map<String, Object> string(String description, int maxLength) {
    return Map.of("type", "string", "description", description, "maxLength", maxLength);
  }

  private static Map<String, Object> text(String value) {
    return Map.of("content", List.of(Map.of("type", "text", "text", value)));
  }

  private static Map<String, Object> failure(String message) {
    return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true);
  }

  private static String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text.");
    return value.asText();
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return UUID.fromString(requiredText(node, field));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(field + " must be a UUID.");
    }
  }

  private static int requiredInt(JsonNode node, String field) {
    if (!node.path(field).canConvertToInt()) throw new IllegalArgumentException(field + " must be an integer.");
    return node.path(field).intValue();
  }

  private static void validateName(String name) {
    if (name.strip().length() > 100) throw new IllegalArgumentException("confirmedName must be at most 100 characters.");
  }

  private static String waterFingerprint(UUID plantId, LocalDate date) {
    try {
      var bytes = new ByteArrayOutputStream();
      var data = new DataOutputStream(bytes);
      data.writeUTF("mcp-log-watering-v1");
      data.writeUTF(plantId.toString());
      data.writeUTF(date.toString());
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String fingerprint(UUID plantId, String name) {
    try {
      var bytes = new ByteArrayOutputStream();
      var data = new DataOutputStream(bytes);
      data.writeUTF("mcp-confirm-plant-name-v1");
      data.writeUTF(plantId.toString());
      data.writeUTF(name.strip());
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
