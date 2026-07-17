# Plant Care Assistant

This repository is becoming a privacy-conscious plant-care assistant. It helps a user decide when
to inspect an individual plant from its environment and recorded care history. It does not issue
fixed-schedule watering commands; the user always makes the watering decision.

## Implemented vertical slice

The Spring Boot API now supports this complete flow:

1. Create an owner-scoped plant profile.
2. Append a timestamped wet, moist, or dry soil observation.
3. Generate and atomically persist a deterministic inspection recommendation.
4. Retrieve the plant's recommendation history with its date window, reason code, explanation,
   rules version, and creation time.

The initial rules are documented in
[`docs/adaptive-inspection-rules.md`](docs/adaptive-inspection-rules.md).

## Structure

- `apps/api` - Java 21/Spring Boot modular monolith with Flyway and embedded H2 persistence.
- `apps/api/src/main/java/com/eternalliquet/plantcare/inspection` - framework-free domain rules.
- `docs` - rule contract and migration notes.

## Run the API

Prerequisites:

- Java 21
- Gradle 8.14.3 or a compatible Gradle 8 release

```sh
cd apps/api
gradle bootRun
```

The API stores local data in `apps/api/data/` by default. That directory is ignored by Git. Set
`DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` to override the connection. Never put
real credentials in the committed `.env.example`.

Then open `http://localhost:8080/api/status` or
`http://localhost:8080/actuator/health`.

## API example

Until authentication is implemented, requests use `X-Owner-Id` as temporary owner context. This
header provides query scoping but is not proof of identity.

Enum JSON values are a stable, case-sensitive API contract. Use `indoor` or `outdoor` for the
environment; `plastic`, `ceramic`, `terracotta`, or `unknown` for pot material; `yes`, `no`, or
`unknown` for drainage; `low`, `medium_indirect`, `bright_indirect`, `direct`, or `unknown` for
light; and `wet`, `moist`, or `dry` for soil state. Responses use the same values.

Create a plant:

```sh
curl -X POST http://localhost:8080/api/plants \
  -H "Content-Type: application/json" \
  -H "X-Owner-Id: 10000000-0000-0000-0000-000000000001" \
  -d '{
    "displayName": "Kitchen fern",
    "knownName": "Boston fern",
    "environment": "indoor",
    "potMaterial": "plastic",
    "drainage": "yes",
    "lightLevel": "bright_indirect",
    "baselineInspectionIntervalDays": 4
  }'
```

Record an observation, substituting the returned plant UUID:

```sh
curl -X POST http://localhost:8080/api/plants/PLANT_ID/observations \
  -H "Content-Type: application/json" \
  -H "X-Owner-Id: 10000000-0000-0000-0000-000000000001" \
  -d '{
    "soilState": "dry",
    "notes": "Pot feels light",
    "observedAt": "2026-07-17T12:00:00Z"
  }'
```

Observation timestamps may be historical or up to five minutes ahead of server time to allow for
normal client clock skew. Later timestamps receive `400 Bad Request` and create no care history.

Retrieve recommendation history:

```sh
curl http://localhost:8080/api/plants/PLANT_ID/recommendations \
  -H "X-Owner-Id: 10000000-0000-0000-0000-000000000001"
```

## Test and build

```sh
cd apps/api
gradle test
gradle build
```

CI uses Gradle 8.14.3 and Java 21. With Docker installed, the same test toolchain can be run from
the repository root:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace/apps/api \
  gradle:8.14.3-jdk21 gradle test --no-daemon
```

## Current limitations

- There is no active UI.
- `X-Owner-Id` is not authentication; a real authenticated identity boundary is still required.
- The embedded H2 store is suitable for this first slice, not a multi-instance production service.
- Rule version `inspection-rules-v1` uses only soil state and the baseline interval. The saved
  environment fields do not yet change the recommendation.
- Watering events, photos, identification, notifications, weather, and sensor inputs are deferred.

## Next recommended milestone

Add append-only watering-event recording and use observation/watering pairs to calculate a
plant-specific drying-cycle history. Keep the recommendation deterministic and explain which
historical facts adjusted the next inspection window.
