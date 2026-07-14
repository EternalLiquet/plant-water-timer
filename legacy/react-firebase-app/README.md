# Legacy React/Firebase App

This folder preserves the original Create React App/Firebase implementation for reference during migration.

Current legacy shape:

- React 17 with Create React App 4.
- Material UI v4.
- Firebase Auth and Firestore are initialized directly in `src/App.js`.
- Data is stored in a single Firestore `plants` collection with `plantName`, `wateringGap`, `lastWatering`, `nextWatering`, and `uid` fields.
- The app uses a fixed watering interval and does not model observations or adaptive care recommendations.

The Firebase web config values in `src/App.js` identify the public Firebase client project. They are not service-account credentials, but this code should not be copied into the new backend architecture.
