# Repository Guide for AI-Assisted Development

## Product purpose

This application is becoming a privacy-conscious plant-care assistant. Its primary purpose is
to help users decide when to inspect and care for each individual plant based on plant type,
environment, and recorded history. It is not a repeating watering timer.

The product should eventually support:

- Plant profiles and plant photos.
- Ranked plant-identification candidates and manual plant identification.
- Structured care profiles.
- Soil and plant observations and watering-event history.
- Adaptive inspection recommendations with explanations.
- Reminders and notifications.
- Privacy-conscious image handling.
- Optional self-hosted plant-identification inference.

## Current repository shape

- `apps/api` is the active Java 21/Spring Boot modular monolith.
- `apps/api/src/main/java/com/eternalliquet/plantcare/inspection` contains deterministic domain
  rules. Keep this package independent of Spring, HTTP, databases, system time, and networks.
- `apps/api/src/main/java/com/eternalliquet/plantcare/plants` contains the current application,
  persistence, and HTTP slice. Keep controllers thin and owner scoping in the service and SQL.
- `apps/api/src/main/resources/db/migration` contains append-only Flyway migrations.
- The current embedded H2 database is a deliberately small first persistence step. Do not force
  a database or framework migration without an approved decision and an incremental plan.
- There is no active UI and no authentication provider. `X-Owner-Id` is temporary owner context,
  not proof of identity. Do not present it as a security boundary.
- `docs/adaptive-inspection-rules.md` is the contract for the implemented rule version.

## Core product principles

- Never blindly instruct users to water on a fixed schedule.
- Recommend when to inspect the plant, not when to water automatically.
- Users must make the final watering decision.
- Plant-identification results must be ranked candidates, never asserted certainty.
- Identification confidence must be visible, and manual identification must always remain
  available.
- Structured rules and recorded history are the source of truth.
- An LLM may explain a recommendation later but must not invent care decisions.
- Historical observations and watering events should be append-only where practical.
- Recommendation decisions must be explainable and reproducible.
- Store the rule version with every generated recommendation.
- Avoid botanical or diagnostic certainty when evidence is limited.
- Keep the app useful when external identification services are unavailable.

## Architectural guardrails

- Build a modular monolith first, with clear feature and domain boundaries. Do not introduce
  microservices, Kafka, Kubernetes, event buses, or distributed workflows without a demonstrated
  requirement.
- Keep domain logic deterministic and minimally coupled to UI or server frameworks. Pass time
  explicitly into domain rules; inject `Clock` at application boundaries.
- Keep plant-identification providers behind an interface. Do not depend directly on one paid AI
  provider.
- Store future image objects in object storage, not PostgreSQL binary columns. Store only object
  references and metadata in the relational database.
- Use UTC `Instant` values and UUIDs for new persistent identifiers.
- Make schema changes only with a new database migration. Never edit a migration that may have
  been applied.
- Make critical multi-record writes transactional. Observations and their generated
  recommendations must succeed or fail together.
- Scope every query and mutation of user-owned data by the authenticated owner. Until real
  authentication exists, preserve the current owner scoping and clearly document its limitation.
- Use optimistic locking where concurrent updates to mutable records matter. Prefer append-only
  facts for observations, watering events, and generated recommendations.
- Validate strictly at HTTP, storage, file, and provider trust boundaries. Keep database checks
  for important invariants as a second line of defense.
- Use structured, privacy-conscious logging. Do not log credentials, tokens, raw photos, precise
  locations, or sensitive user content.
- Never commit secrets or real credentials. Document configuration with `.env.example`.
- When photo support is added, validate content type, decoded format, size, dimensions, and object
  key ownership; strip EXIF metadata by default and never trust filenames.
- If location is introduced, collect coarse location by default and require an explicit reason
  before collecting anything more precise.
- Reuse technologies already present unless a migration has been intentionally approved. Avoid
  speculative abstractions that have no current consumer.

## Testing and TDD rules

- All behavioral work must begin with a failing test. Use the red-green-refactor cycle.
- Run the new test and confirm it fails for the expected reason before adding production behavior.
- Tests should describe user-visible or domain-visible behavior.
- Do not add production behavior before the corresponding failing test exists.
- Do not weaken tests to make broken behavior pass or change expectations to match an incorrect
  implementation.
- Prefer deterministic unit tests for domain rules. Add integration tests at database, storage,
  and HTTP boundaries where relevant.
- Mock only true external boundaries. Do not replace meaningful database integration with mocks.
- Avoid tests that merely verify internal method calls and avoid snapshot tests for core
  recommendation logic.
- Time-dependent tests must use an injectable clock or explicit timestamp. Random behavior must
  use a seeded or replaceable source.
- Every bug fix must include a regression test. Test names should state the behavior and condition.
- Run the smallest relevant test set throughout development, then the complete suite before
  completion.

From `apps/api`, the standard checks are:

```sh
gradle test
gradle build
```

The repository currently has no Gradle wrapper. CI uses Gradle 8.14.3 with Java 21. If local
versions are unavailable, run the same toolchain in Docker from the repository root:

```sh
docker run --rm -v "$PWD:/workspace" -w /workspace/apps/api \
  gradle:8.14.3-jdk21 gradle test --no-daemon
```

## Development workflow

1. Inspect the repository, current patterns, Git state, and applicable instructions before editing.
2. Fetch the remote, fast-forward local `master`, confirm a clean tree, and create a fresh branch
   with a conventional name such as `feat/adaptive-plant-inspection`.
3. Reuse existing patterns where sensible and make the smallest coherent change. Avoid unrelated
   refactors and keep commits focused.
4. Work test-first and run focused tests during each red-green-refactor loop.
5. Run formatting, linting, compilation, type checks, tests, build validation, and existing Docker
   checks where applicable. Report every check that cannot be run.
6. Review the complete diff. Check tracked and staged files for secrets, generated files, local
   environment files, uploaded photos, databases, and accidental artifacts.
7. Use Conventional Commits, push the feature branch, and open a pull request targeting `master`.
   Never merge without explicit approval.

## Definition of done

- Acceptance behavior is implemented through the existing architecture.
- Behavioral tests were written first, failed for the expected reason, and now pass.
- Relevant edge cases and owner isolation are covered.
- Documentation and rule contracts are current.
- Formatting, compilation, tests, and the production build pass, or limitations are reported.
- No secrets, local credentials, databases, uploads, generated output, or unrelated changes are
  included.
- The complete diff has been reviewed.
- A focused Conventional Commit exists on a feature branch.
- The feature branch is pushed and a pull request is open against `master` without being merged.
- Known limitations and the recommended next milestone are documented.
