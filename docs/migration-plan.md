# Migration Plan

## Current State

The repository started as a small Create React App hosted through Firebase. Most behavior lived in `src/App.js`, including Firebase initialization, Google sign-in, Firestore reads and writes, plant creation, watering updates, and table rendering.

Useful concepts from the old app were:

- A user-owned plant list.
- Basic plant create, edit, delete, and water actions.

The parts to replace are:

- Direct Firebase coupling in the UI.
- Fixed `wateringGap` scheduling as the source of truth.
- Boilerplate CRA tests and generated Firebase Hosting workflows.
- Console logging of signed-in user data.

## Decision

The legacy React/Firebase app has been removed instead of retained in-tree because it exposed Firebase client configuration and is unlikely to be reused. New groundwork starts in `apps/api` as a minimal Gradle-based Spring Boot service targeting Java 21.

The first adaptive-care vertical slice now adds owner-scoped plant profiles, append-only soil
observations, deterministic inspection recommendations, and recommendation history. Persistence
uses Flyway-managed embedded H2 so the behavior can be proven transactionally before selecting
production infrastructure.

## Next Steps

1. Add authenticated identity and replace the temporary `X-Owner-Id` trust boundary.
2. Record append-only watering events and derive a plant-specific drying-cycle history.
3. Move from embedded H2 to PostgreSQL when deployment needs durable multi-instance storage.
4. Add curated care profiles and manual plant identity selection.
5. Add an object-storage boundary before accepting plant photos.
6. Introduce ranked plant-identification providers only after manual identity and photo privacy
   controls exist.
