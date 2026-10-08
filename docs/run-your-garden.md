# Run your own garden

This page is for the person operating the server. People using the app only need its address
and garden password. They do not need Java, Python, a database account, API keys or a terminal.

## Supported first release

A private, single-garden server using Java 21 and Python 3.12 on Linux x86-64, plus a current
mobile or desktop browser. H2 is a single-process embedded database. Do not run multiple API
instances against the same data directory. Photo identification uses CPU inference on this
server; photos are not uploaded to an outside AI provider. The server needs several GB of free
disk for dependencies, about 400 MB for model data, and enough RAM for Java plus the model
worker. Measure resource use on the intended host before deployment.

No server has been deployed by this implementation. Phone camera/home-screen installation
requires a reachable HTTPS address supplied by the operator. A local `localhost` address on
someone's computer is not reachable from a phone.

## 1. Prepare local identification

Follow [the identification setup](../apps/identify/README.md). It installs pinned official
runtime packages and verifies the pretrained model download. Keep the model service on its
fixed loopback address, `127.0.0.1:8765`; never expose it publicly. Start it before the Java app.
A missing or unavailable model leaves manual naming usable, but that is not a verified
photo-identification release.

## 2. Build and start the app

With a Java 21 **JDK** installed, from the repository root:

```sh
cd apps/api
umask 077
./gradlew test build
# Choose a private password; this reads it without displaying it or putting it in shell history.
read -r -s -p 'Garden password (12+ characters): ' GARDEN_PASSWORD; echo
export GARDEN_PASSWORD
java -jar build/libs/plant-care-api-0.1.0-SNAPSHOT.jar
```

Open `http://localhost:8080`. Sign in with the password you chose. The username is fixed to
`gardener` and is hidden from the ordinary sign-in form. The password must contain at least
12 characters and at most 72 UTF-8 bytes. No default password is shipped. Without one, sign-in
is disabled and the screen says setup is unfinished. The password is not written by the app.
Use your service manager's secret mechanism in a real deployment. Don't put secrets in Git.

The Java server binds to `127.0.0.1` by default and refuses non-loopback bind addresses. Its session is HttpOnly, SameSite=Strict, expires
after eight hours of inactivity, and state changes require CSRF protection. The old experimental
`/api/plants` owner-header routes are blocked in the running app; new routes derive ownership
from the authenticated session. This is **one private garden**, not a multi-user hosted service.
Sharing its password shares access to all of its plants and photos.

## 3. Provide a phone-friendly address (separate operator work)

Use a maintained HTTPS reverse proxy and access controls appropriate to your home/private
network. Keep both backend services private. Set `SECURE_COOKIES=true` for HTTPS and use a
stable origin; do not expose the development HTTP port or the identification port to the
internet. Public hosting, account provisioning, firewall changes and certificates require their
own operator decisions and are not performed by this code change. Internet-facing deployments
need additional edge abuse controls, monitoring and an independent security review.

On a supported phone browser, open that address, sign in, then use **Add to Home Screen** or
**Install app**. The app offers an install button only when the browser supplies one. A file
picker is always available alongside camera capture. HEIC is not supported in this first
release: choose a JPG or PNG. Photos must be no larger than 5 MB / 20 megapixels.

## Dates while traveling

Each plant keeps the time zone of the device that added it. “Today” and check dates continue to
use that plant’s original zone if your phone travels. Entered watering dates are calendar dates,
not timestamps shifted across midnight. There is no extra setup question or zone selector in this
first release.

## Data and backup

By default, starting from `apps/api` puts H2 files in `data/` and sanitized JPEG photos in
`data/photos/`. Keep the working directory stable, or set explicit paths. Data/photo directories are created
owner-only (0700), and photo files are 0600. Existing shared-permission directories are rejected
rather than silently changing their permissions; the operator must choose a private directory.
This first release is POSIX-only. Backups must keep the same private access restrictions. Plant history is in
the database, while image bytes are separate files. Uploaded location/EXIF metadata is removed;
orientation is applied before metadata is discarded. Images are reduced to a maximum 1600px
long edge. The original uploaded filename is not stored. Bounded multipart input and image decoding stay
in memory rather than using an EXIF-bearing image cache on disk. Cancel/replacement removes an unused
photo when the browser can finish that request; interrupted uploads can leave unclaimed
photos. These expire after 24 hours and are removed on the next upload or hourly cleanup while
the service is running. Referenced plant photos are never removed by this cleanup. Cleanup failures
are retried rather than silently forgetting files; the operator must fix storage errors. The initial
garden is bounded to 200 plants/photos.

Back up the database **and** photo directory together while the Java service is stopped. Keep
backups private. Restore both together, then restart and verify plant counts, photos and recent
history. Never delete the data folder to fix a startup error. Database schema upgrades run via
append-only Flyway migrations; take a backup before updating. A changing working directory can
look like an empty garden, so verify the data path before creating new records.

The MVP has watering Undo but no account deletion/export UI. For complete removal, stop the
services and remove the correct garden's data and backups under your own retention policy.
That operation is destructive; don't automate it or confuse it with uninstalling a home-screen
shortcut. The shortcut does not own the server's records. The operator is responsible for
requests to remove or export data in this private first release.

## Configuration

| Variable | Purpose | Default |
| --- | --- | --- |
| `GARDEN_PASSWORD` | Private garden sign-in password | none; sign-in disabled |
| `SERVER_PORT` | Java HTTP port | 8080 |
| `SERVER_ADDRESS` | Java bind address; loopback only | 127.0.0.1 |
| `SECURE_COOKIES` | Required with HTTPS | false for localhost development |
| `DATABASE_URL` | H2 JDBC file location | `jdbc:h2:file:./data/plant-care;...` |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Operator database credentials | H2 local defaults |
| `PHOTO_DIRECTORY` | Sanitized photo directory | `./data/photos` |
| `IDENTIFY_URL` | Loopback-only model HTTP endpoint | `http://127.0.0.1:8765/identify` |
| `IDENTIFY_MODEL_DIR` | Python model data directory | `apps/identify/model` |

No photo or plant record is cached by the service worker. Only the public app shell is cached.
The server must be reachable to view or save private data. Offline writes are never silently
queued or reported as saved. No push notifications or background watering alerts are included.

## Troubleshooting

- **“This garden is not ready yet”**: operator must set the garden password and restart.
- **“Identification isn't available”**: check the companion `/health` locally and its verified
  model files. Restart the companion after a timed-out/crashed worker. Manual names remain usable.
- **“Not saved”**: reconnect and retry. Repeated watering saves are deduplicated. Refresh after
  session expiry and sign in again.
- **No photos after moving the app**: check the database and photo paths; restore them together.
- **Forgot the password**: the operator can replace its configured value and restart Java, which
  invalidates in-memory sessions. Keep the same data path. There is no password-reset email.
