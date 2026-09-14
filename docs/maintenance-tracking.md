# Maintenance tracking

Garage vehicles, maintenance history, preferences, and the dashboard
status calculation. See `docs/repair-v2-architecture.md` sections 1, 5,
and 6 for the schema and orchestration design; this covers the product
behavior.

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
   the proposal (known service type; a real odometer and/or date; ranges
   sane) and only then calls `MaintenanceRepository.createEvent`.
   `created_via = 'chat'`. The model is instructed (system prompt in
   `MotoChatOrchestrationService`) to only populate a proposal for a
   clearly-stated, non-hypothetical fact — never for "I don't know" or a
   "what if I were at X km" question. This is enforced twice: by the
   prompt, and independently by the backend validation, which never trusts
   the model's own framing.

The chat response's `actionsTaken` array (rendered as a small green
confirmation chip, e.g. "Engine oil saved · 19,000 km") is built only
from writes the backend actually performed — never from what the model
merely said it would do.

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
UNKNOWN    no verified interval fact for this bike, or no last event recorded
OK         > 20% of the interval remains
DUE_SOON   0–20% of the interval remains
DUE        overdue by up to 20% of the interval
OVERDUE    overdue by more than 20% of the interval
```

Distance-based (km) and time-based (months) intervals are evaluated
independently when both exist for a service type (currently only engine
oil has both — "every 6,000 km or 6 months", matching the source
Markdown); whichever produces the more urgent status wins, per the "6,000
km OR 6 months, whichever comes first" rule in the spec. Worked numeric
examples (23,500 km / 19,000 km last service / 6,000 km interval ⇒ 1,500
km remaining, not overdue; 25,400 km ⇒ slightly overdue; combined
distance-OK-but-time-overdue) are covered in
`MaintenanceStatusServiceTest`.

A service type with no reliably-extracted interval fact (most of the
taxonomy today — only `ENGINE_OIL_CHANGE` and `VALVE_CLEARANCE_CHECK`
have distance facts; `ENGINE_OIL_CHANGE`, `COOLANT_CHANGE`,
`BRAKE_FLUID_CHANGE` have time facts) always shows `UNKNOWN` with an
explanatory note ("No verified interval for this service on this bike")
rather than a guessed status.

## Known limitation

Conditional maintenance triggers written in prose ("lubricate after
washing or riding in the rain") are not encoded into the dashboard's
status logic — the dashboard only reasons about distance/time intervals.
The chat assistant *does* reason about these conversationally (it has the
full text passage as retrieved evidence), matching spec section 27's
"the dashboard does not need to perfectly encode every conditional rule."
