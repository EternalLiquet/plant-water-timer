# Optional local photo identification

A small internal CPU companion for the Spring application. It returns ranked suggestions,
not a confirmed identity. No API key, paid inference API, or third-party photo upload is used.
“Inference local” means on the same machine as the API, **not in the user's browser**.
This process never persists submitted images. The Spring app owns any separately requested
photo retention. No watering or toxicity decisions should be based on model guesses.

## Setup (Python 3.12, Linux x86-64 CPU)

From the repository root:

```sh
python3.12 -m venv apps/identify/.venv
apps/identify/.venv/bin/pip install 'torch==2.8.0+cpu' --index-url https://download.pytorch.org/whl/cpu
apps/identify/.venv/bin/pip install -r apps/identify/requirements.lock
apps/identify/.venv/bin/python apps/identify/download_model.py
apps/identify/.venv/bin/python apps/identify/app.py
```

The dependency lock records the tested exact environment, including transitive dependencies.
The CPU PyTorch wheel comes from the official PyTorch registry. Other pinned packages come
from PyPI. Other operating systems/architectures need their official CPU PyTorch wheel and
separate validation. Expect roughly 400 MB for model data and several GB free for the Python
runtime. No GPU is required. Set `IDENTIFY_MODEL_DIR` to use another **local** model directory;
download there with `download_model.py --directory PATH` first.

The explicit one-time download fetches four allowlisted data files at the immutable revision
in `model.json`, checks byte sizes and SHA-256 digests, and retains the upstream model card.
It does not execute any repository code, download pickle weights, or enable `trust_remote_code`.
The service verifies all model files again at startup and uses explicit Transformers ViT
classes, `local_files_only=True`, safetensors-only loading, offline mode and disabled telemetry.
Runtime requests do not cause model downloads. A missing/corrupt model leaves the endpoint
unavailable, with manual identification still usable in the main app.

Run a single service instance with `python app.py`; it binds **127.0.0.1:8765** only and disables
access logs. Do not expose this unauthenticated internal service publicly or run multiple
workers. No deployment, firewall or network-access changes are made by these scripts.

## HTTP contract

- `GET /health`: 200 `{ "status": "ready", "modelVersion": "repository@revision" }`,
  or 503 with status `unavailable`.
- `POST /identify`: raw JPEG/PNG body; matching `Content-Type: image/jpeg` or `image/png`.
  It accepts no URL, filename, multipart form, model selector or remote fetch target.
- 200 body:

```json
{
  "candidates": [
    { "scientificName": "Monstera deliciosa", "confidence": 0.72 },
    { "scientificName": "Monstera adansonii", "confidence": 0.12 }
  ],
  "modelVersion": "domai-tb/OpenPlants-Identification-ViT-Base-Patch16-224@30794da002827ad922366fe88840c5a2d8a4a31d",
  "status": "suggestions",
  "manualFallback": true
}
```

The response contains up to five candidates ordered by descending softmax score (0–1).
These are **uncalibrated model scores, not probabilities of a correct identification**.
`status` is `unknown` when the top score is below 0.5 or its margin over second place is
below 0.1; otherwise `suggestions`. This is only a UI heuristic. It cannot reliably detect
non-plants or unknown species; even a confident result can be wrong. Always require user
selection/confirmation and retain manual identification. Unsupported care species must keep
manual care-profile selection rather than being mapped from an arbitrary candidate.

Limits: 5 MiB encoded, 20 million decoded pixels, 8192 pixels per side, still images only,
10 seconds upload, one in-flight request, two CPU threads for inference, 15 seconds inference.
Decoded RGB pixels are oriented using EXIF, all metadata is dropped, and images are reduced
to at most 1024×1024 before passing pixel bytes to the worker. Preprocessing then uses the
pinned model's 224×224 transform. Responses use `Cache-Control: no-store`.

Failures: 413 size/dimensions, 415 MIME/format mismatch, 422 corrupt/animated image,
408 upload deadline, 429 busy with `Retry-After: 2`, 503 unavailable/failed/timed-out model.
A hard inference deadline terminates the worker. **Restart the service** after a worker
failure; it deliberately does not queue jobs or continuously retry expensive model startup.
The Spring client should use a bounded ~30-second request timeout and show manual fallback.

## Model provenance and limitations

- [Model and declared Apache-2.0 license](https://huggingface.co/domai-tb/OpenPlants-Identification-ViT-Base-Patch16-224/tree/30794da002827ad922366fe88840c5a2d8a4a31d)
- [Pinned model card](https://huggingface.co/domai-tb/OpenPlants-Identification-ViT-Base-Patch16-224/blob/30794da002827ad922366fe88840c5a2d8a4a31d/README.md)
- [Apache-2.0 license text](https://www.apache.org/licenses/LICENSE-2.0)

Independently inspected upstream API metadata, config and preprocessor on 2026-10-08.
Standard `ViTForImageClassification`, 14,829 species labels, 224×224 input. Labels include
Monstera deliciosa, Epipremnum aureum, Ficus elastica and Aloe vera. Weights:
388,831,924 bytes; SHA-256 `d02b1982e5082f268bef1843a1799d8abaea588627ef50c9e2afe0ef55173244`.
The publisher declares Apache-2.0 and describes GBIF/iNaturalist training data. That is
publisher provenance, not an independent training-data rights audit or accuracy benchmark.
The pinned card is saved with the weights for attribution. Model weights are not committed.

## Validation

```sh
apps/identify/.venv/bin/python -m unittest discover -s apps/identify -p 'test_*.py'
apps/identify/.venv/bin/python apps/identify/smoke.py
# Optional known, licensed local image. No fixture is fetched or uploaded automatically.
apps/identify/.venv/bin/python apps/identify/smoke.py --fixture /path/to/plant.jpg
```

Tests use a fake model only at the true model boundary and include actual subprocess
termination on timeout. They cover ordered result contract, manual fallback, MIME/content
checks, size/dimension limits, unavailable behavior, EXIF stripping, animation rejection and
checksum tampering. Cancellation/retry coverage verifies the single-flight slot remains held
until the bounded CPU operation finishes, including repeated cancellation, and checks no
response mixing or premature image closure. They do **not** measure the real model's recognition quality.

Verification on 2026-10-08: all 12 API/security tests passed; official CPU dependency
imports and compile checks passed. Real HTTP health (200) and photo identification (200)
also passed against the running loopback service with the same Monstera result and manual
fallback present; the smoke server was stopped afterward. All model file hashes verified after one authorized retry
of a cancelled setup download. Actual CPU synthetic inference passed in 0.317 seconds.
A licensed public Monstera leaf ranked **Monstera deliciosa** first (score 0.6874), ahead of
Epipremnum aureum (0.0333), Epipremnum pinnatum (0.0286), and Monstera adansonii (0.0278).
This is a one-image qualitative spot-check, **not** an accuracy benchmark; the fixture may
also overlap the publisher's unknown training set. No real user photos were used.

Fixture: [Monstera deliciosa Leaf 2700px.jpg](https://commons.wikimedia.org/wiki/File:Monstera_deliciosa_Leaf_2700px.jpg),
photo ©2006 Derek Ramsey, location credit Chanticleer Garden;
[CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).
Downloaded unchanged for a local test only; runtime preprocessing resized it and stripped metadata.
No fixture image is redistributed with this code. SHA-256:
`a868ec8d06ae2db74318b3be535ef8e9116d107248f02d2102e48d7d5de95f19`.

The synthetic gate checks execution only. A representative held-out photo set and non-plant
rejection evaluation are still required before making broader recognition-quality claims.
