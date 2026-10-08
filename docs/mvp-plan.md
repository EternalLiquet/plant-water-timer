# Smallest useful plant-care app

## Four actions

1. Take or choose a picture, then confirm a likely plant name.
2. Record when you last watered it.
3. See a suggested next watering **check** date.
4. Tap **Watered today** when you have watered it.

A suggested date is a prompt to check the soil, not proof that watering is needed. The first
version uses a clearly editable check interval (7 days initially), not unverified species-specific
watering claims. Identification and watering advice are separate. Existing deterministic soil
inspection rules remain intact.

## Delivery decision

One mobile-friendly web app, served by the current Java 21 / Spring Boot service. Embedded H2
stores plant and care records; sanitized photo objects stay on disk outside the database. An
optional Python CPU inference companion identifies plants privately without a paid API key.
The app can be added to a home screen on supported browsers. The service must be running; this
is not an offline-sync app. A phone needs an operator-provided HTTPS address. Local development
binds to loopback, and this work does not deploy or expose a network service.

Use one private garden with a normal password/session initially. Do not expose the development
owner-header API publicly. A multi-household service with signup, account recovery and quotas is
outside this release, rather than a falsely claimed security feature.

## Identification decision

OpenPlants ViT supplies ranked candidate names and scores, which the user must confirm. The
model is declared Apache-2.0 and covers 14,829 labels, including common houseplants. The
smaller PlantNet-300K MobileNet model was rejected because its labels omit pothos, Monstera,
spider plant, peace lily, Aloe and other common houseplants. No custom model-repository code is
executed. Model files are pinned and verified by checksum. See `apps/identify` for setup,
resource limits, exact revision, license and inference verification status.

Scores are not guarantees. Unknown plants, missing model files and unreadable pictures always
have an honest manual-name path. No photo goes to a third-party identification service.

## Interface

Dark charcoal, muted lavender, original delicate flower decoration, clear white text and green
saved states. Inspiration is a gentle floral mood, not official character art or affiliation.
Short labels: **Add plant**, **Take a photo**, **Plant name**, **Last watered**, **Check again in**,
**Save plant**, **Watered today**, **Undo**. Ordinary date/number inputs, no API jargon.

## Backlog and release gates

- #4: photo-to-watering epic; #6 identification, #7 care history, #8 interface.
- #5: install/private delivery epic; #10 private setup, #9 verification.
- Based on PR #3; do not silently merge it or repeat its implementation.
- Require full API tests/build, phone-size browser testing, data survival, actual known-fixture
  inference, safe photo handling and a reviewable draft PR.
- Live HTTPS deployment, actual-device installation and a novice usability session remain
  explicit external validation gates until performed. Mock inference alone is not a completed
  photo-identification MVP.
