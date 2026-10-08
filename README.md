# Little Garden · Plant Care Assistant

A phone-first, private plant-care app: add a plant from a photo, confirm a likely name, record
watering and see the next suggested check. Tap **Watered today** when you have watered it.

- Original dark floral interface with short labels and normal date inputs.
- Local CPU plant identification with ranked, uncertain candidates and manual correction.
- Durable Spring Boot / H2 plant and watering history; accidental watering can be undone.
- Private password/session access, protected photos and no third-party photo inference.
- Optional home-screen installation on supported browsers; the server must be reachable.

A suggested date is a reminder to check the soil, not an instruction to water blindly. The first
version uses an editable 7-day starting interval. It does not claim AI-derived watering advice.

## Start here

- [Run your garden: operator setup, privacy, backup and phone access](docs/run-your-garden.md)
- [Local AI setup and verified model provenance](apps/identify/README.md)
- [Verification and remaining release gates](docs/mvp-validation.md)
- [MVP scope and GitHub work](docs/mvp-plan.md)
- [Original deterministic soil-inspection contract](docs/adaptive-inspection-rules.md)

This branch builds on unmerged PR #3. Review and merge decisions remain separate. See the
validation report for exactly what has and has not been tested; no live service is deployed.

## Develop and test

```sh
cd apps/api
./gradlew test build
# From repository root:
node --test tests/*.test.mjs
```

Java 21 JDK is required. Python 3.12 is required for the local model companion. The Gradle wrapper
pins the build tool. `apps/api` serves the static client, API and embedded database;
`apps/identify` is a bounded, loopback-only inference process. No microservices platform,
paid API key or model training is needed.

The earlier `X-Owner-Id` examples are development API history, not production authentication.
Those routes are intentionally blocked in the running consumer app. Use its normal sign-in.
