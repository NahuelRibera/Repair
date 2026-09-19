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
2. **Conversational**: the chat model proposes zero or more entries in
   `proposedMaintenanceEvents` (a LIST — see "Multiple actions in one
   message" below) and optionally a `proposedOdometerUpdate`, in its
   structured response; `MotoChatOrchestrationService` validates each
   proposal independently and only then calls
   `MaintenanceRepository.createEvent`. `created_via = 'chat'`.

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
"maybe", "probably", "don't remember"), planned-future ("I should", "I
might", "tomorrow", "soon"), and future-conditional-mileage ("when/once I
reach/hit X km") language, and vetoes **any** proposed action —
maintenance event or odometer update — if found, regardless of what
`intent` the model claimed. Either layer can block; neither alone can
approve (`validateAndApplyProposedActions` requires both:
`intent == CONFIRMED_COMPLETED` AND the guard not blocking). This is
deliberately conservative — a false block only costs an extra turn; a
false write corrupts garage data. See `ActionIntentGuardTest` for the
exact QA-reported false-positive and true-positive examples, and
`MotoChatOrchestrationServiceTest`'s scenario A–I matrix for the
integration-level proof, including the specific reported bug ("If I were
at 25,000 km, what maintenance would be due?" incorrectly saving an event
at 25,000 km) as its own regression test
(`scenarioF_hypotheticalMileage_neverSavesEventOrUpdatesOdometer`).

For a proposal that carries a specific numeric value (an odometer
reading), the guard is scoped with `blocksValueMention(userText,
anchorValue)` to whichever sentence(s) actually mention that number,
rather than the whole message: a real rider message is often more than
one sentence, and an unrelated trailing hedge in a later sentence ("...I
should get the chain looked at soon") must not veto a clearly confirmed,
unrelated statement earlier in the same message ("The odometer now reads
20,000 km."). It falls back to the old whole-message `blocksAction` check
when the number can't be located verbatim in any single sentence — never
less safe than the whole-message check, only more precise when it can be.
See `unrelatedTrailingHedgeClauseDoesNotBlockAConfirmedOdometerStatementInTheSameMessage`.

Every proposal (executed or rejected) is recorded as a `ProposalAuditEntry`
in `moto_rag_runs.actions_taken` — visible in Evidence & Debug (with the
rejection reason) even though the normal chat UI only ever shows executed
actions.

### Never claim a write succeeded until it really succeeded

A proposed action is only ever reported to the rider as executed — an
`actionsTaken` entry, which is what drives the green "✓ Odometer updated"
confirmation badge in `MotoAnswerCard` — after the underlying database
write has been verified to have actually happened, never merely because
the repository call returned without throwing. `MaintenanceRepository.createEvent`
and `VehiclePreferenceRepository.upsert` are safe by construction for
this (`INSERT ... RETURNING id` and `INSERT ... ON CONFLICT ... DO
UPDATE` respectively always affect exactly one row, or throw). The one
write that was **not** safe by construction was a plain `UPDATE ...
WHERE id = ...` with no ownership filter and no rows-affected check —
`GarageVehicleRepository.updateOdometerIfOwned` replaces it: scoped by
`user_id`, and returning the persisted value via `RETURNING
current_odometer_km` as an `Optional<Double>` — present means exactly one
row matched and that's the value now actually stored; empty means zero
rows matched (wrong id, wrong owner, or the vehicle was deleted since the
read), which `validateAndApplyProposedActions` treats as a rejected
proposal, never as a successful update. See
`odometerUpdateWithZeroRowsAffectedIsTreatedAsFailure`. The model's own
`summary`/`contextUsed` text is instructed to use neutral acknowledgment
language ("noted") rather than persistence-claiming words ("saved",
"updated", "recorded") — only the backend-verified `actionsTaken` badge
may claim a write actually happened.

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

### Multiple actions in one message

`proposedMaintenanceEvents` is a LIST, not a single nullable object — a
rider confirming several distinct services in one message ("I changed the
oil and oil filter at 24,000 km") must produce one entry per service,
validated and persisted independently (own intent, own guard check, own
correction handling). See
`multipleConfirmedMaintenanceActionsInOneMessageCreateSeparateEvents`.

### Corrections (isCorrection) and the same-day backstop

A rider correcting a value they just stated ("I changed the oil at 20,000
km" → "I actually changed it at 19,000 km") must update the existing row
in place, never leave two conflicting rows for one real service. The
model can flag this explicitly (`isCorrection: true` on that proposal),
which routes to `MaintenanceRepository.correctLatestEvent` — an `UPDATE`
keyed by true insertion order (`id DESC`, never `performed_at`, since the
model has no reliable "today" anchor) that only overwrites the fields the
correction actually supplies.

Live QA showed the model does not reliably set `isCorrection` even when a
correction is obvious, so the backend also has a deterministic backstop:
`MaintenanceRepository.hasEventRecordedToday` + a same-day-repeat-implies-
correction rule for service types where a genuine second occurrence in
one calendar day isn't realistic (`SAME_DAY_REPEAT_IMPLIES_CORRECTION` in
`MotoChatOrchestrationService` — deliberately excludes `CHAIN_LUBE`/
`CHAIN_ADJUSTMENT`, where lubricating twice in a day, e.g. after a ride
and after a wash, is a real, separate event that must never be merged).
See `unflaggedSameDayRepeatIsStillTreatedAsACorrection` and
`unflaggedSameDayChainLubeRepeatIsNeverAutoMerged`.

### Contextual follow-up answers

A rider's reply to Repair's OWN immediately preceding follow-up question
is often short and only makes sense in that context ("Did you replace the
oil filter at 19,000 km as well?" → "yes"; "When did you last replace the
oil filter?" → "the oil filter at 12000"). This only works because the
conversation history replayed to the model on the next turn includes that
follow-up question text — `message.content()` for a stored assistant turn
is only `answer.summary()`, which never contained it, so without fixing
this the model had nothing unambiguous to resolve a short reply against.
`MotoChatOrchestrationService.reconstructAssistantContent` restores it
from the already-persisted `structured_response` JSON (no schema change).
See `assistantHistoryReconstructionIncludesThePreviousTurnsFollowUpQuestion`
and `contextualYesAfterOilFilterFollowUpCreatesOilFilterEventNotAirFilter`.

### Relative mileage ("X km ago")

The model is instructed to compute the absolute event mileage itself —
`eventMileage = currentOdometer - relativeDistance` — from the known
current odometer, and populate `odometerKm` with that computed value; it
never leaves a raw relative distance for the backend to interpret, and
never fabricates an absolute mileage when the current odometer is
unknown. Live-verified: "I changed the air filter 2,000 km ago" with a
25,000 km current odometer persisted an `AIR_FILTER_CHANGE` at 23,000 km.

### Negative ("never done") statements

"I have never replaced the spark plugs" must never create a fake
replacement event — there is nothing to record. The model is instructed
to treat it as read-only context for reasoning about due/overdue status,
never as a `proposedMaintenanceEvents` entry. See
`neverDoneStatementProposesNoMaintenanceEvent`.

### Future-event rejection

A maintenance event describes a PAST action, so its mileage can never
legitimately exceed the bike's current odometer — "I changed the oil at
30,000 km" while the bike is really at 25,000 km is rejected, never
silently persisted. The check uses an *effective* ceiling: a same-turn
`proposedOdometerUpdate` (not yet written to the database when the
maintenance-event loop runs) counts too, so "I'm now at 25,000, I just
changed the oil at 25,000" is correctly accepted rather than compared
against the stale pre-turn value. See
`maintenanceEventMileageExceedingCurrentOdometerIsRejected` and
`maintenanceEventAtTheSameTurnsNewOdometerValueIsNotTreatedAsFutureInconsistent`.

### Component replacement with product details (tires, battery, ...)

A confirmed tire/battery replacement is a real maintenance event exactly
like an oil change, even though it has no fixed verified interval
(condition-based, not scheduled) — the model is given an explicit worked
example so it doesn't skip adding the entry just because it has no
interval to compare against. Product/position detail the rider gives
("rear", "Dunlop Trail Max Raid") goes into that entry's `notes` field.

The frontend has a matching fix: `StatusCardView`'s `UNKNOWN` branch (no
verified interval — see below) used to render only the "no interval" note
and silently drop `lastOdometerKm`/`lastPerformedAt` even when the
backend had them. `UNKNOWN` means "no verified interval", not "no
history" — a completed tire replacement is still real history and must
stay visible, just without a due-date calculation. Fixed in
`apps/web/src/app/garage/[id]/page.tsx`.

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

## Rejected actions never pollute canonical state

A proposed maintenance event or odometer update that fails deterministic
validation (future mileage, not grounded in the current turn, out of
range, a lower-than-current value without explicit confirmation, or a
write that affected zero rows) is never persisted, and its value is
scrubbed from that turn's own `confirmedFacts`/`contextUsed` before the
answer is returned — see `scrubValues` and the `valuesToScrub` list in
`MotoChatOrchestrationService.validateAndApplyProposedActions`. This
matters because a turn's persisted `message.content()` (the summary) is
exactly what `reconstructAssistantContent` replays into later turns'
history, so an unscrubbed rejected claim would otherwise resurface as
"confirmed" fact in a future turn even though nothing was ever written.

When a single rejected future-mileage maintenance event is the *only*
thing a turn proposed, the entire answer is replaced with a deterministic
clarification ("That service mileage (X km) is higher than the bike's
current recorded odometer of Y km...") instead of scrubbing the model's
own (possibly persistence-claiming) prose piecemeal. Scrubbing is
deliberately **not** applied to intent-classification or language-guard
rejections (`UNCERTAIN_PAST`, `PLANNED_FUTURE`, `HYPOTHETICAL`, or
`ActionIntentGuard` blocks) — those are legitimate to surface as
clearly-labeled, non-persisted context, per the negative/uncertain
statement handling above.

## Current-turn grounding

A proposed action's numeric value must trace back to the current turn:
either verbatim in the rider's own message, computable as a relative
delta ("2,000 km ago" against the stored current odometer), or present in
Repair's own immediately preceding follow-up question (via
`reconstructAssistantContent`). See `groundedInCurrentTurn`. Previously
stored context (the ownership block, older turns) is *input*, never
grounds a *new* action on its own — this is the deterministic fix for two
related bug classes: a maintenance event proposed from stale context the
rider never restated this turn, and a spurious no-op odometer-update
badge triggered merely because the current odometer was visible in
context.

## Latest service is chosen by mileage, not by insertion order

`MaintenanceRepository.listForOwnedVehicle`, `recentEvents`, and
`latestEventByType` all order by `odometer_km DESC NULLS LAST` (falling
back to date, then true insertion order, only for the rare event with no
odometer at all) — never by `created_at`/row id. A rider is free to enter
real history out of chronological order (e.g. a 24,000 km service logged
before a 22,000 km one); the bike's own mileage timeline decides what
"latest" means for the dashboard and for `MaintenanceStatusService`, not
which row happened to be inserted last or mentioned most recently in
chat.

## Corrections require explicit correction intent — and the backend, not the model, decides

An earlier version of this backend treated "same service type + same
calendar day + different mileage" as sufficient evidence of a correction
(`SAME_DAY_REPEAT_IMPLIES_CORRECTION`). That was removed: it incorrectly
collapsed legitimate, out-of-order same-day history into a single
overwritten row. The very next fix then relied purely on the model's own
`isCorrection` flag — but live QA immediately showed that flag isn't
trustworthy either: the model set `isCorrection: true` on "I changed the
engine oil and oil filter at 24,000 km" with a 20,000 km event already on
record, and *no correction language present at all*, silently overwriting
real history exactly like the removed heuristic did.

The model's `isCorrection` flag is therefore no longer consulted at all
for this decision. `MotoChatOrchestrationService.hasCorrectionEvidence`
is the sole, deterministic authority, and it works in both directions:

- **Evidence downgrades a false positive.** No explicit correction
  language in the rider's current message ⇒ always a new, independent
  event, regardless of what the model claimed.
- **Evidence forces a true positive.** Explicit correction language in
  the rider's current message ⇒ always a correction, even if the model
  said `isCorrection: false`.

Evidence is either (a) `CORRECTION_LANGUAGE_PATTERNS` matching anywhere
in the current message — "actually", "correction", "I meant", "wasn't"/
"isn't", a "`X, not Y`" digit-contrast, or a message opening with "No" —
all of which are self-sufficient and need no conversational context; or
(b) a short (≤6 word) reply that opens with a negation/apology ("No…",
"Sorry…") and contains a number, but *only* when Repair's own immediately
preceding message was itself an explicit confirm-or-correct question
(`isExplicitConfirmOrCorrectQuestion` — "is that correct?", "can you
confirm?", etc.) — a bare "Sorry, 19,000." only means anything as a
correction in that narrow context, never standalone.

None of the following trigger a correction on their own, matching the
explicit non-examples this guard was built against: a new (even lower)
mileage for an already-recorded service type, a same-day repeat, "I also
changed X at Y", "There was another X at Y", or "I changed X at Y and
again at Z".

### Which row a correction targets

Deciding *whether* to correct is only half the problem — deciding
*which* stored event to update is a separate question, and
`MaintenanceRepository.correctLatestEvent`'s original answer ("whichever
event of this service type was inserted most recently", `id DESC`) is
only safe when at most one event of that type exists since the value
actually being corrected. A real conversation broke this: a rider had
engine oil logged at 20,000 km, then logged a genuinely separate later
oil change at 22,000 km, then said "the oil change at 20,000 km was at
19,000 km" — naming the OLD value explicitly. `correctLatestEvent`
targeted the 22,000 km row (the most recently inserted one) instead,
silently destroying that real event while leaving the actual 20,000 km
mistake uncorrected.

`MaintenanceRepository.correctEventAtMileage` is the fix: when
`MotoChatOrchestrationService.extractOldMileageBeingCorrected` finds
exactly one other mileage-shaped number in the rider's current message
besides the proposed new value, the correction targets the stored event
that actually holds that old value (`WHERE odometer_km = :oldOdometerKm`)
instead of guessing by insertion order. `correctLatestEvent` remains the
fallback — used when no old value is named (e.g. "Sorry, I meant 19,000
km.") or when no event is found at the named old value — so a correction
is never simply lost.

## Deleting a motorcycle

`GarageVehicleRepository.deleteVehicleAndAllData` is a hard,
`@Transactional` delete — the vehicle's earlier soft-delete
(`deleted_at`) mechanism is gone, since it left every child row
permanently orphaned with no restore/undo UI ever built on top of it. No
FK in this schema cascades (see `V7__garage_and_maintenance.sql`'s header
comment), so the child tables are deleted by hand, child-first:
`moto_retrieved_evidence` → `moto_rag_runs` → `moto_chat_messages` →
`moto_chat_sessions` → `{maintenance_events, vehicle_preferences}` →
`garage_vehicles`. Ownership-scoped throughout (an `isOwned` pre-check
plus a final `WHERE user_id` re-check on the vehicle row itself); a
non-owned or unknown vehicle id deletes nothing and the method returns
`false`. `DELETE /api/garage/vehicles/{id}` (unchanged route/shape, now
hard-deleting) is the only entry point — the frontend's `ConfirmDialog`
in My Garage is the only thing that can reach it.

## Known limitation

Conditional maintenance triggers written in prose ("lubricate after
washing or riding in the rain") are not encoded into the dashboard's
status logic — the dashboard only reasons about distance/time intervals.
The chat assistant *does* reason about these conversationally (it has the
full text passage as retrieved evidence).
