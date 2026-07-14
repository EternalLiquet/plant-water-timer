# Plant Water Timer

This repository is being modernized from a legacy React/Firebase watering timer into a plant-care application.

## Structure

- `apps/api` - minimal Spring Boot API groundwork.
- `legacy/react-firebase-app` - preserved original Create React App/Firebase app.
- `docs/migration-plan.md` - current migration notes and next steps.

## Run the API

Prerequisites:

- Java 21
- Gradle 8+

```sh
cd apps/api
gradle bootRun
```

Then open:

- `http://localhost:8080/api/status`
- `http://localhost:8080/actuator/health`

## Test

```sh
cd apps/api
gradle test
```
