# Maintenance tracking

Garage vehicles, maintenance history, preferences, and the dashboard
status calculation. See `docs/repair-v2-architecture.md` sections 1, 5,
and 6 for the schema and orchestration design; this covers the product
behavior.

## Garage vehicle identity (reuse vs. duplicate)

Selecting a bike through the normal flows (landing page, "Choose your
bike", chat's "+ Add another bike" picker) reuses an existing garage
vehicle for the same (visitor, model, year) rather than silently creating
a duplicate physical motorcycle — `POST /api/garage/vehicles` defaults to
`allowDuplicate: false`, which makes `GarageVehicleController.create`
look up `GarageVehicleRepository.findExisting` first. The **My Garage**
page's own "+ Add another bike" is the one place that passes
`allowDuplicate: true`, since owning two identical motorcycles is a
legitimate case that flow exists specifically to support. Reused
selection returns `200 OK`; a genuinely new vehicle returns `201
Created`. Starting a new chat about an already-selected bike always
creates a new *conversation* (`moto_chat_sessions` row) — that's correct
and expected (see "Same-bike conversation isolation" below) — it just
never creates a new *garage vehicle* unless explicitly asked to. See
`GarageVehicleRepositoryIT` and `GarageVehicleControllerTest`.

## Same-bike conversation isolation

Multiple chats about the same garage vehicle share its current odometer,
maintenance history, and preferences (all read fresh from
`garage_vehicles`/`maintenance_events`/`vehicle_preferences` on every
turn) but keep independent message histories per `moto_chat_sessions`
row. Two different garage vehicles — even identical make/model/year, from
the explicit-duplicate flow above — never share data; every read/write is
scoped by `garage_vehicle_id`, itself ownership-checked against the
visitor cookie.

## Data model

- `garage_vehicles` — one row per rider's motorcycle (visitor-owned,
  references a `motorcycle_models` row + a year, not a free-text vehicle).
- `maintenance_events` — one row per confirmed service (oil change, chain
  lube, ...), with an odometer reading, a date, or both.
- `vehicle_preferences` — personal setup (currently: tire pressure by
  context — ROAD/OFF_ROAD/WET/TRACK), **never** conflated with
  manufacturer reference data (`motorcycle_facts`). See "Facts vs.
  preferences" below.

## How an event gets created

Two paths, both landing in the same `maintenance_events` table:

1. **Manual**: `POST /api/garage/vehicles/{id}/maintenance` from the
   maintenance dashboard's "+ Record maintenance" form.
   `created_via = 'manual'`.
2. **Conversational**: the chat model proposes a
   `proposedMaintenanceEvent` (and optionally a `proposedOdometerUpdate`)
   in its structured response; `MotoChatOrchestrationService` validates
   the proposal and only then calls `MaintenanceRepository.createEvent`.
   `created_via = 'chat'`.

The chat response's `actionsTaken` array (rendered as a small green
confirmation chip, e.g. "Engine oil saved · 19,000 km") is built only
from writes the backend actually performed — never from what the model
merely said it would do.

### Action confidence (the false-positive/false-negative fix)

Every proposal carries an `intent` classification the model must set —
`CONFIRMED_COMPLETED`, `UNCERTAIN_PAST`, `PLANNED_FUTURE`, `HYPOTHETICAL`,
`QUESTION`, `RECOMMENDATION`, or `UNKNOWN` (see `MotoDiagnosticAnswer`,
`MotoDiagnosticResponseSchema.INTENT_VALUES`, and the "PROPOSING ACTIONS"
section of the system prompt in `MotoChatOrchestrationService`). **Only
`CONFIRMED_COMPLETED` is ever written.** For `UNCERTAIN_PAST` the model is
instructed to ask a short confirmation question instead of proposing
("Do you want me to record that as a confirmed oil change, or is it only
an estimate?") — live-verified to work exactly this way.

This alone would still depend entirely on the model correctly classifying
its own intent every time. `ActionIntentGuard` is a second, independent,
deterministic check: it scans the rider's own raw message this turn for
hypothetical ("if I", "what if"), uncertain ("I think", "not sure",
"maybe", "probably", "don't remember"), and planned-future ("I should",
"I might", "tomorrow", "soon") language, and vetoes **any** proposed
action — maintenance event or odometer update — if found, regardless of
what `intent` the model claimed. Either layer can block; neither alone
can approve (`validateAndApplyProposedActions` requires both:
`intent == CONFIRMED_COMPLETED` AND `!ActionIntentGuard.blocksAction(userText)`).
This is deliberately conservative — a false block only costs an extra
turn; a false write corrupts garage data. See `ActionIntentGuardTest` for
the exact QA-reported false-positive and true-positive examples, and
`MotoChatOrchestrationServiceTest`'s scenario A–I matrix for the
integration-level proof, including the specific reported bug ("If I were
at 25,000 km, what maintenance would be due?" incorrectly saving an event
at 25,000 km) as its own regression test
(`scenarioF_hypotheticalMileage_neverSavesEventOrUpdatesOdometer`).

Every proposal (executed or rejected) is recorded as a `ProposalAuditEntry`
in `moto_rag_runs.actions_taken` — visible in Evidence & Debug (with the
rejection reason) even though the normal chat UI only ever shows executed
actions.

### Odometer independence

A maintenance event's mileage is a fact about *when that service was
performed* — it never writes to `garage_vehicles.current_odometer_km` by
itself. Only an explicit `proposedOdometerUpdate` (its own
`CONFIRMED_COMPLETED` intent, its own guard check) touches the stored
odometer. A lower proposed value than what's currently stored is only
accepted if the same number also appears verbatim in the rider's own
message this turn (`userMentionsNumber`) — otherwise it's silently
dropped, never silently regressing the odometer from a misread. Live- and
unit-tested: recording a past oil change at a lower mileage than the
current odometer leaves the odometer untouched
(`scenarioH_pastMaintenanceMileageNeverOverwritesCurrentOdometer`).

## Facts vs. preferences

`motorcycle_facts` (manufacturer reference data, from ingestion) and
`vehicle_preferences` (rider-entered personal setup) are different tables
with no shared write path — a preference upsert can never touch a fact
row, and vice versa (see
`GarageVehicleRepositoryIT.preferenceUpsertNeverTouchesTheManufacturerFactsTable`).
The chat system prompt instructs the model to present them distinctly
when both exist ("the bike-specific road recommendation is X; you
previously saved Y as your off-road setup") rather than letting one
silently look like the other.

## Dashboard status calculation

`MaintenanceStatusService` (pure Java, no LLM) computes one status card
per service type:

```
UNKNOWN                    no verified interval fact for this bike at all
INTERVAL_KNOWN_NO_HISTORY  interval is verified, but no previous service is recorded
OK                         > 20% of the interval remains
DUE_SOON                   0–20% of the interval remains
DUE                        overdue by up to 20% of the interval
OVERDUE                    overdue by more than 20% of the interval
```

`UNKNOWN` and `INTERVAL_KNOWN_NO_HISTORY` are deliberately distinct
states, not just different note text — "we don't know the interval" and
"we know the interval but not your history" are different facts and the
dashboard badge/copy differ accordingly ("Unknown" vs. "Interval known").
For `INTERVAL_KNOWN_NO_HISTORY` with a known current odometer still short
of one full interval, the card shows a best-effort "next scheduled at the
interval mark" figure (assuming no prior service) — e.g. a 42,000 km
valve-clearance interval with the bike at 18,500 km shows "Next
scheduled: ~42,000 km · 23,500 km remaining", caveated with "No previous
service recorded". If the odometer already exceeds the interval, no
remaining/next-scheduled figure is shown at all (that assumption of "zero
prior service" could easily be wrong for a used bike) — an honest "we
don't know" beats a possibly-fabricated "overdue".

Distance-based (km) and time-based (months) intervals are evaluated
independently when both exist for a service type; whichever produces the
more urgent status wins, per the "whichever comes first" rule. Worked
numeric examples are covered in `MaintenanceStatusServiceTest`.

### Fact-to-service mapping

`KM_INTERVAL_FACT` / `MONTH_INTERVAL_FACT` in `MaintenanceStatusService`
map each `ServiceType` to the `motorcycle_facts` fact_type(s) that back
it. As of this QA pass:

| Service type | km fact | months fact |
|---|---|---|
| `ENGINE_OIL_CHANGE` | `ENGINE_OIL_INTERVAL_KM` | `ENGINE_OIL_INTERVAL_MONTHS` |
| `OIL_FILTER_CHANGE` | `OIL_FILTER_INTERVAL_KM` | `OIL_FILTER_INTERVAL_MONTHS` |
| `AIR_FILTER_CHANGE` | `AIR_FILTER_INTERVAL_KM` | — |
| `CHAIN_LUBE` | `CHAIN_LUBE_INTERVAL_KM` | — |
| `SPARK_PLUG_CHANGE` | `SPARK_PLUG_REPLACE_INTERVAL_KM` | `SPARK_PLUG_REPLACE_INTERVAL_MONTHS` |
| `VALVE_CLEARANCE_CHECK` | `VALVE_CLEARANCE_INTERVAL_KM` | — |
| `COOLANT_CHANGE` | — | `COOLANT_CHANGE_INTERVAL_MONTHS` |
| `BRAKE_FLUID_CHANGE` | — | `BRAKE_FLUID_INTERVAL_MONTHS` |
| `CHAIN_ADJUSTMENT`, `TIRE_REPLACEMENT`, `BATTERY_REPLACEMENT` | *(none)* | *(none)* |

The last three are genuinely condition/wear-based in the real knowledge
base (e.g. "check before each ride, adjust when outside spec"), not
scheduled intervals — they correctly stay `UNKNOWN` rather than getting a
fabricated number. The oil-filter and spark-plug km/months figures are
*derived* (never fabricated) from real consecutive numbers in the source
Markdown — e.g. spark-plug replacement points "13,000 and 25,000 km" ⇒
12,000 km interval — see `docs/knowledge-ingestion.md` and
`pipelines/embeddings/motorcycle_facts.py`'s `_extract_spark_plug_interval`
/ `_extract_oil_filter_interval`. Covered by
`MaintenanceStatusServiceTest.factMappingMatrix_recognizesEveryExtractedIntervalType`
and `serviceTypesWithNoExtractableInterval_gracefullyStayUnknown`, and by
`pipelines/tests/test_motorcycle_facts.py` against the real Yamaha corpus.

## Context relevance and provenance separation

`MaintenanceContextRelevance.filter` keeps stored maintenance history out
of the chat prompt unless it's actually relevant to the question asked
(keyword-matched per service type, or always included for a generic
"what's due" style question) — fixes a reported bug where an overheating
question pulled in unrelated chain-lubrication history.

The structured answer separates `confirmedFacts` (verified
manufacturer/bike-specific facts only) from `contextUsed` (the rider's
own stored history/preferences/conversation facts) — enforced by the
system prompt and rendered as two visually distinct sections ("Verified
bike facts" vs. "Your bike") in `MotoAnswerCard`. `MotorcycleFactLabels`
centralizes human-readable fact labels so a raw internal key like
`ENGINE_OIL_INTERVAL_KM` is never sent to the model as a label to copy in
the first place (previously the prompt said "prefer these verbatim,"
which invited exactly that leak); the frontend also has a defensive
`humanize()` regex as a second line of defense.

## Known limitation

Conditional maintenance triggers written in prose ("lubricate after
washing or riding in the rain") are not encoded into the dashboard's
status logic — the dashboard only reasons about distance/time intervals.
The chat assistant *does* reason about these conversationally (it has the
full text passage as retrieved evidence).
