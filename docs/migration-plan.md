# Migration Plan

## Current State

The repository started as a small Create React App hosted through Firebase. Most behavior lives in `src/App.js`, including Firebase initialization, Google sign-in, Firestore reads and writes, plant creation, watering updates, and table rendering.

The useful concepts to preserve are:

- A user-owned plant list.
- Basic plant create, edit, delete, and water actions.
- The existing background image asset if it still fits the future product.

The parts to replace are:

- Direct Firebase coupling in the UI.
- Fixed `wateringGap` scheduling as the source of truth.
- Boilerplate CRA tests and generated Firebase Hosting workflows.
- Console logging of signed-in user data.

## Decision

The legacy app has been moved to `legacy/react-firebase-app` and left intact for reference. New groundwork starts in `apps/api` as a minimal Gradle-based Spring Boot service targeting Java 21.

This keeps the first PR intentionally small: it establishes the backend application shape without prematurely building the plant-care vertical slice.

## Next Steps

1. Add persistence with PostgreSQL and Flyway.
2. Define owner-scoped plant profile endpoints.
3. Add plant photo metadata and storage boundaries.
4. Introduce the plant identification provider interface with a mock implementation.
5. Add the adaptive recommendation domain model and tests.
6. Add the Expo mobile app once the API contract is stable enough to consume.
