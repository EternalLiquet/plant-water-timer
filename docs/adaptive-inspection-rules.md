# Adaptive Inspection Rules v1

## Purpose

`inspection-rules-v1` turns one soil observation and a plant's baseline inspection interval into
a safe, deterministic window for the next inspection. It never decides that the user must water.

## Inputs

- Soil state: `WET`, `MOIST`, or `DRY`.
- Baseline inspection interval: an integer from 1 through 365 days.
- Observation timestamp: an explicit UTC instant used as the date-calculation anchor.
- Creation timestamp: an explicit UTC instant stored with the recommendation.

At the application boundary, the observation timestamp must be no later than the injected server
clock plus a five-minute client clock-skew allowance. The exact limit is valid, as are timestamps
within the allowance and all historical timestamps. A later timestamp is rejected before the
observation or recommendation is persisted.

The plant profile also stores environment, pot material, drainage, light level, and an optional
known name. Version 1 does not yet use those fields to adjust dates.

## Outputs

Every persisted recommendation contains:

- Minimum, recommended, and maximum inspection timestamps.
- One structured reason code.
- A human-readable explanation mapped from that reason code.
- Rules version `inspection-rules-v1`.
- Creation timestamp.

For every result, `minimum <= recommended <= maximum`, and no generated inspection timestamp is
before the observation timestamp.

## Date calculations

All offsets below are whole UTC days added to the observation timestamp. `B` is the validated
baseline interval in days.

| Soil state | Minimum offset | Recommended offset | Maximum offset | Reason code |
| --- | ---: | ---: | ---: | --- |
| Wet | `B` | `B + 1` | `B + 2` | `SOIL_WET_DELAY_INSPECTION` |
| Moist | `max(0, B - 1)` | `B` | `B + 1` | `SOIL_MOIST_USE_BASELINE` |
| Dry | `0` | `floor(B / 2)` | `B` | `SOIL_DRY_INSPECT_SOONER` |

Wet soil delays the recommended inspection beyond the baseline to allow more drying time. Moist
soil keeps the recommended inspection at the baseline with a one-day window. Dry soil opens the
window immediately and recommends an earlier check; for a one-day baseline, that recommendation
is at the observation time.

## Explanation mapping and safety wording

Explanations are defined once in the domain's reason-code mapping and stored with each generated
recommendation. Controllers and clients display that explanation rather than inventing care
advice.

- Wet explains that the soil remains wet and needs more drying time.
- Moist explains that moisture remains and the plant should be inspected later rather than cared
  for from a schedule.
- Dry says to inspect the plant and confirm whether watering is appropriate.

No explanation may say “Water now,” prescribe “Water every X days,” or imply certainty that
watering is required. The structured reason, inputs, output dates, and stored rules version make a
decision reproducible.

## Persistence and ownership

The observation and recommendation are append-only records written in one database transaction.
If either insert fails, neither is retained. Plant access, observation creation, and recommendation
history queries are scoped by both plant UUID and owner UUID.

## Known limitations

- Version 1 does not learn from watering events or previous drying cycles.
- Environment, pot, drainage, light, species, season, and weather do not adjust the interval yet.
- It does not assess plant health or diagnose a condition.
- The current owner header is temporary request context, not authentication.
- Changing these calculations requires a new rules version; previously stored recommendations
  must retain `inspection-rules-v1`.
