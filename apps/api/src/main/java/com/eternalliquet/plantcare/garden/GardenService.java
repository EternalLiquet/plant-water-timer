package com.eternalliquet.plantcare.garden;

import com.eternalliquet.plantcare.plants.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class GardenService {
  private final JdbcClient db;
  private final PlantCareService care;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final Object[] createLocks =
      java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

  GardenService(
      JdbcClient db,
      PlantCareService care,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.db = db;
    this.care = care;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  record Plant(
      UUID id,
      String name,
      String species,
      UUID photoId,
      LocalDate lastWatered,
      LocalDate nextCheck,
      int intervalDays,
      UUID lastEventId,
      String zone) {}

  record Water(UUID id, LocalDate date, Instant createdAt, boolean undone) {}

  record Create(
      UUID requestId,
      String name,
      String species,
      UUID photoId,
      LocalDate lastWatered,
      int intervalDays,
      String zone) {}

  record WaterRequest(UUID eventId, LocalDate date, String zone) {}

  private static ResponseStatusException missing() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Plant not found.");
  }

  private void owned(UUID owner, UUID id, boolean lock) {
    if (db.sql(
            "SELECT id FROM plants WHERE id=:id AND owner_id=:owner" + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .param("owner", owner)
        .query(UUID.class)
        .optional()
        .isEmpty()) throw missing();
  }

  @Transactional(readOnly = true)
  List<Plant> list(UUID owner) {
    return db
        .sql("SELECT id FROM plants WHERE owner_id=:owner ORDER BY created_at DESC LIMIT 200")
        .param("owner", owner)
        .query(UUID.class)
        .list()
        .stream()
        .map(id -> get(owner, id))
        .toList();
  }

  @Transactional(readOnly = true)
  Plant get(UUID owner, UUID id) {
    return db.sql(
            "SELECT"
                + " id,display_name,known_name,photo_id,baseline_inspection_interval_days,garden_zone"
                + " FROM plants WHERE id=:id AND owner_id=:owner")
        .param("id", id)
        .param("owner", owner)
        .query(
            (rs, n) -> {
              var latest =
                  db.sql(
                          "SELECT id,watered_date,next_check FROM watering_events WHERE"
                              + " owner_id=:owner AND plant_id=:id AND undone_at IS NULL ORDER BY"
                              + " watered_date DESC,created_at DESC,id DESC LIMIT 1")
                      .param("owner", owner)
                      .param("id", id)
                      .query(
                          (r, k) ->
                              new Object[] {
                                r.getObject("id", UUID.class),
                                r.getObject("watered_date", LocalDate.class),
                                r.getObject("next_check", LocalDate.class)
                              })
                      .optional();
              Object[] w = latest.orElse(new Object[] {null, null, null});
              return new Plant(
                  id,
                  rs.getString("display_name"),
                  rs.getString("known_name"),
                  rs.getObject("photo_id", UUID.class),
                  (LocalDate) w[1],
                  (LocalDate) w[2],
                  rs.getInt("baseline_inspection_interval_days"),
                  (UUID) w[0],
                  rs.getString("garden_zone"));
            })
        .optional()
        .orElseThrow(GardenService::missing);
  }

  Plant create(UUID owner, Create request) {
    // Bound memory and hold the stripe through COMMIT, not merely through the annotated method.
    // This app intentionally supports one embedded-database instance; SQL uniqueness remains.
    int stripe = Math.floorMod(Objects.hash(owner, request.requestId()), createLocks.length);
    synchronized (createLocks[stripe]) {
      return transactions.execute(tx -> createInTransaction(owner, request));
    }
  }

  private Plant createInTransaction(UUID owner, Create request) {
    if (request.name() == null || request.name().isBlank() || request.name().strip().length() > 100)
      throw new IllegalArgumentException("Give your plant a name, up to 100 characters.");
    if (request.species() != null && request.species().length() > 100)
      throw new IllegalArgumentException("Use a plant type up to 100 characters.");
    WateringPolicy.nextCheck(LocalDate.now(clock), request.intervalDays());
    validateDate(request.lastWatered(), request.zone());
    String fingerprint = createFingerprint(request);
    if (request.requestId() != null) {
      var existing =
          db.sql("SELECT id FROM plants WHERE owner_id=:owner AND client_request_id=:request")
              .param("owner", owner)
              .param("request", request.requestId())
              .query(UUID.class)
              .optional();
      if (existing.isPresent()) {
        String saved =
            db.sql("SELECT create_fingerprint FROM plants WHERE id=:id AND owner_id=:owner")
                .param("id", existing.get())
                .param("owner", owner)
                .query(String.class)
                .single();
        if (!fingerprint.equals(saved))
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This plant was already saved with different details. Refresh to see it before making"
                  + " changes.");
        return get(owner, existing.get());
      }
    }
    if (db.sql("SELECT COUNT(*) FROM plants WHERE owner_id=:owner")
            .param("owner", owner)
            .query(Integer.class)
            .single()
        >= 200) throw new IllegalArgumentException("This garden can hold up to 200 plants.");
    if (request.photoId() != null
        && db.sql("SELECT id FROM garden_photos WHERE id=:id AND owner_id=:owner FOR UPDATE")
            .param("id", request.photoId())
            .param("owner", owner)
            .query(UUID.class)
            .optional()
            .isEmpty()) throw new IllegalArgumentException("Please choose the photo again.");
    var p =
        care.createPlant(
            owner,
            new CreatePlantCommand(
                request.name(),
                request.species(),
                PlantEnvironment.INDOOR,
                PotMaterial.UNKNOWN,
                Drainage.UNKNOWN,
                LightLevel.UNKNOWN,
                request.intervalDays()));
    db.sql(
            "UPDATE plants SET"
                + " photo_id=:photo,client_request_id=:request,garden_zone=:zone,create_fingerprint=:fingerprint"
                + " WHERE id=:id AND owner_id=:owner")
        .param("photo", request.photoId())
        .param("request", request.requestId())
        .param("zone", request.zone() == null ? "UTC" : request.zone())
        .param("fingerprint", fingerprint)
        .param("id", p.id())
        .param("owner", owner)
        .update();
    if (request.lastWatered() != null)
      water(
          owner,
          p.id(),
          new WaterRequest(UUID.randomUUID(), request.lastWatered(), request.zone()));
    return get(owner, p.id());
  }

  @Transactional
  Plant water(UUID owner, UUID id, WaterRequest request) {
    owned(owner, id, true);
    if (request.eventId() == null || request.date() == null)
      throw new IllegalArgumentException("Choose a watering date.");
    String plantZone = get(owner, id).zone();
    validateDate(request.date(), plantZone);
    var existing =
        db.sql("SELECT plant_id,owner_id,watered_date FROM watering_events WHERE id=:event")
            .param("event", request.eventId())
            .query(
                (r, n) ->
                    List.of(
                        r.getObject("plant_id", UUID.class),
                        r.getObject("owner_id", UUID.class),
                        r.getObject("watered_date", LocalDate.class)))
            .optional();
    if (existing.isPresent()) {
      if (!existing.get().equals(List.of(id, owner, request.date())))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "That save request was already used. Please refresh.");
      return get(owner, id);
    }
    // Separate repeated taps on the same calendar day are still one watering record.
    if (db.sql(
                "SELECT COUNT(*) FROM watering_events WHERE plant_id=:id AND owner_id=:owner AND"
                    + " watered_date=:date AND undone_at IS NULL")
            .param("id", id)
            .param("owner", owner)
            .param("date", request.date())
            .query(Integer.class)
            .single()
        > 0) return get(owner, id);
    int days = get(owner, id).intervalDays();
    db.sql(
            "INSERT INTO"
                + " watering_events(id,plant_id,owner_id,watered_date,zone_id,next_check,rule_version,created_at)"
                + " VALUES(:event,:id,:owner,:date,:zone,:next,:version,:now)")
        .param("event", request.eventId())
        .param("id", id)
        .param("owner", owner)
        .param("date", request.date())
        .param("zone", plantZone)
        .param("next", WateringPolicy.nextCheck(request.date(), days))
        .param("version", WateringPolicy.VERSION)
        .param("now", clock.instant())
        .update();
    return get(owner, id);
  }

  @Transactional
  Plant undo(UUID owner, UUID id, UUID event) {
    owned(owner, id, true);
    int count =
        db.sql(
                "UPDATE watering_events SET undone_at=COALESCE(undone_at,:now) WHERE id=:event AND"
                    + " plant_id=:id AND owner_id=:owner")
            .param("now", clock.instant())
            .param("event", event)
            .param("id", id)
            .param("owner", owner)
            .update();
    if (count == 0) throw missing();
    return get(owner, id);
  }

  @Transactional(readOnly = true)
  List<Water> history(UUID owner, UUID id) {
    owned(owner, id, false);
    return db.sql(
            "SELECT id,watered_date,created_at,undone_at FROM watering_events WHERE plant_id=:id"
                + " AND owner_id=:owner ORDER BY watered_date DESC,created_at DESC LIMIT 100")
        .param("id", id)
        .param("owner", owner)
        .query(
            (r, n) ->
                new Water(
                    r.getObject("id", UUID.class),
                    r.getObject("watered_date", LocalDate.class),
                    r.getObject("created_at", OffsetDateTime.class).toInstant(),
                    r.getObject("undone_at") != null))
        .list();
  }

  private void validateDate(LocalDate date, String zone) {
    ZoneId z;
    try {
      z = ZoneId.of(zone == null ? "UTC" : zone);
    } catch (DateTimeException ex) {
      throw new IllegalArgumentException("Choose a valid time zone.");
    }
    if (date != null
        && (date.isAfter(LocalDate.now(clock.withZone(z)))
            || date.isBefore(LocalDate.of(1900, 1, 1))))
      throw new IllegalArgumentException("Choose today or a past watering date after 1900.");
  }

  private static String createFingerprint(Create request) {
    try {
      var bytes = new java.io.ByteArrayOutputStream();
      var data = new java.io.DataOutputStream(bytes);
      data.writeUTF("garden-create-v1");
      data.writeUTF(request.name().strip());
      data.writeUTF(request.species() == null ? "" : request.species().strip());
      data.writeUTF(request.photoId() == null ? "" : request.photoId().toString());
      data.writeUTF(request.lastWatered() == null ? "" : request.lastWatered().toString());
      data.writeInt(request.intervalDays());
      data.writeUTF(request.zone() == null ? "UTC" : request.zone());
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
