# Repair V2 — architecture (motorcycle pivot)

Design for closing the gaps identified in `docs/repair-v2-current-state.md`.
Everything here is additive to the existing stack (Java/Spring, JDBC,
Flyway, Python ingestion, Next.js) — no new technology, no shared table
repurposed from the car prototype.

## 1. Data model (new Flyway migrations, V6+)

### V6 — motorcycle catalog + knowledge

```
motorcycle_manufacturers
  id, canonical_name, slug (unique)

motorcycle_models
  id, manufacturer_id, canonical_name, slug
  unique(manufacturer_id, canonical_name)
  -- "MT-09" and "MT-09 SP" are different rows. "Ténéré 700" and
  -- "Ténéré 700 World Raid" are different rows. Never merged.

motorcycle_model_aliases
  id, model_id, alias, normalized_alias (generated: lower/unaccent/
  hyphen-collapsed)
  unique(model_id, normalized_alias)
  -- "Ténéré 700" / "Tenere 700" / "T7" can all point at the same
  -- model_id. Aliases are scoped to one model_id — never used to merge
  -- two different model_ids together.

motorcycle_knowledge_documents
  id, model_id, year_from, year_to, market, source_relative_path (unique;
  e.g. "knowledge/motorcycles/yamaha/mt-07/2025.md" — git-relative, never
  an absolute filesystem path, never exposed to the frontend), aliases
  (text[], copy of frontmatter for quick lookup), last_verified (date),
  content_hash, ingested_at, updated_at

motorcycle_knowledge_chunks
  id, document_id, section, subsection, category (maintenance |
  troubleshooting | specification | electrical | model_specific | other,
  derived from the heading path — see chunking rules below), heading,
  section_path, content, content_hash, token_count, embedding vector(1536)
  unique(document_id, content_hash)

motorcycle_facts
  id, document_id, fact_type (enum-like text — see fact list below),
  value_numeric, value_text, unit, raw_source_text, created_at
  unique(document_id, fact_type)
  -- deterministic, regex/structure-extracted at ingestion time from the
  -- standardized Markdown, never LLM-inferred (see section 4).
```

Selectable years for a model are *derived*, not stored:
`SELECT generate_series(year_from, year_to) FROM motorcycle_knowledge_documents WHERE model_id = ?`,
deduplicated. A model with no ingested document simply doesn't appear in
the catalog — there is no separate "catalog" table an ingestion job has to
remember to also update.

### V7 — garage + maintenance

```
garage_vehicles
  id, visitor_id (uuid, same VisitorContext cookie as car sessions),
  model_id (-> motorcycle_models), year, market, nickname,
  current_odometer_km, created_at, updated_at

maintenance_events
  id, garage_vehicle_id, service_type (enum-like text — small controlled
  taxonomy, see below), odometer_km (nullable — a date-only event like
  "brake fluid changed last April" may have no odometer), performed_at
  (date, nullable if only odometer is known), notes, created_via
  ('chat' | 'manual'), created_at

vehicle_preferences
  id, garage_vehicle_id, preference_type ('TIRE_PRESSURE' initially),
  context ('ROAD' | 'OFF_ROAD' | 'WET' | 'TRACK'), data (jsonb — e.g.
  {"frontKpa": 190, "rearKpa": 200}), created_at, updated_at
  unique(garage_vehicle_id, preference_type, context)

moto_chat_sessions / moto_chat_messages / moto_rag_runs /
moto_retrieved_evidence
  -- same shape as diagnostic_sessions/diagnostic_messages/rag_runs/
  -- retrieved_evidence, parallel tables rather than reused ones, because
  -- the car tables FK to vehicle_variants and the motorcycle ones need to
  -- FK to garage_vehicles — a shared table would need a nullable/either-or
  -- FK, which defeats "ownership baked into SQL" by adding a runtime
  -- branch. moto_rag_runs.garage_vehicle_id and
  -- moto_retrieved_evidence.chunk_id -> motorcycle_knowledge_chunks.
```

Service-type taxonomy (kept intentionally small, per spec section 23):
`ENGINE_OIL_CHANGE`, `OIL_FILTER_CHANGE`, `SPARK_PLUG_CHANGE`,
`AIR_FILTER_CHANGE`, `VALVE_CLEARANCE_CHECK`, `CHAIN_LUBE`,
`CHAIN_ADJUSTMENT`, `BRAKE_FLUID_CHANGE`, `COOLANT_CHANGE`,
`BATTERY_REPLACEMENT`, `TIRE_REPLACEMENT`, `OTHER`.

## 2. Ingestion pipeline (Python)

New module `pipelines/embeddings/ingest_motorcycle_knowledge.py`, driven
by a new CLI subcommand, reusing `chunker.chunk_markdown_body` unchanged.

Per file in `knowledge/motorcycles/**/*.md`:

1. Parse frontmatter (`python-frontmatter`). Required:
   `manufacturer`, `model`, `year_from`, `year_to`. Optional: `market`
   (default `null` = all markets), `aliases` (list), `last_verified`.
   Missing a required field raises and aborts that file (logged, not
   silently skipped) — a bad file must not leave a partially-ingested
   document behind.
2. Resolve/create `motorcycle_manufacturers` and `motorcycle_models` rows
   by exact `(manufacturer, model)` text match — **not** via alias
   matching, so a typo in `model` creates a visibly new model rather than
   silently merging into an existing one. Aliases from frontmatter are
   upserted into `motorcycle_model_aliases` for *this* model_id only.
3. Compute `content_hash` (sha256 of raw file bytes). If a
   `motorcycle_knowledge_documents` row already exists for this
   `source_relative_path` with the same hash, skip entirely (no
   re-embedding spend) — same idempotency contract as the car pipeline.
4. On change (new file, or changed hash): upsert the document row, delete
   its existing chunks/facts, re-chunk with `chunk_markdown_body`, assign
   `category` per chunk from the top-level heading (`## Periodic
   maintenance` → `maintenance`, `## Basic troubleshooting` →
   `troubleshooting`, `## Quick specifications` → `specification`, `##
   Electronic rider systems`/`## Model-specific systems` →
   `model_specific`, everything else → `other`), embed each chunk
   (OpenAI `text-embedding-3-small`, batched), insert with
   `ON CONFLICT (document_id, content_hash) DO NOTHING` — identical to the
   car pipeline's duplicate-chunk protection.
5. Run deterministic fact extraction (section 4) over the raw section
   text (not the LLM) and upsert `motorcycle_facts` rows,
   `ON CONFLICT (document_id, fact_type) DO UPDATE`.
6. Files removed from disk since the last run: a `--prune` pass deletes
   `motorcycle_knowledge_documents` rows (cascading chunks/facts) whose
   `source_relative_path` no longer exists on disk. Off by default (so an
   accidental partial `find` doesn't wipe data) — must be explicitly
   requested.

Stable identity for idempotency is `source_relative_path` (the file's
path under `knowledge/motorcycles/`, which already encodes manufacturer/
model/year and is unique by construction) plus content hash — simpler and
more robust than trying to derive an id from frontmatter text, and it's
exactly the "stable identifier based on canonical metadata + content hash"
the spec asks for.

## 3. Dynamic catalog & hard filtering

`MotorcycleCatalogRepository` (Java) exposes three read paths, each a
plain SQL query over the tables above — **no code lists**:

```
GET /api/motorcycles/manufacturers
GET /api/motorcycles/manufacturers/{id}/models
GET /api/motorcycles/models/{id}/years
```

Adding `knowledge/motorcycles/honda/cb650r/2025.md` and re-running
ingestion makes Honda/CB650R/2025 appear through these three endpoints
with zero source change anywhere else — proven by the extensibility test
(section 7).

Selecting manufacturer+model+year resolves (server-side) to the specific
`motorcycle_knowledge_documents` row(s) whose `[year_from, year_to]` range
contains the selected year, for that `model_id`. This resolved
`(model_id, year)` pair — not free text — is what a garage vehicle stores
and what retrieval filters on:

```sql
WHERE c.document_id IN (
  SELECT id FROM motorcycle_knowledge_documents
  WHERE model_id = :modelId AND year_from <= :year AND year_to >= :year
)
```

applied identically to both the vector leg and the full-text leg *before*
ranking (same shape as the existing car `RetrievalService`), so an MT-07
query can structurally never rank an MT-09 chunk — it's excluded from the
candidate pool, not down-weighted. If the filter returns zero documents
(no knowledge for that exact model+year), retrieval returns empty and the
orchestration layer answers "bike-specific verified information is
unavailable" rather than falling back to a different model or year.

## 4. Deterministic facts layer

`motorcycle_facts` is populated by regex/structure extraction over known
section shapes in the standardized Markdown (e.g. `### Engine oil` →
`**Quantity:** ... Oil and filter change: 2.60 L.` → 
`ENGINE_OIL_CAPACITY_WITH_FILTER = 2.60 L`). Extraction is
best-effort and conservative: a section whose text doesn't match an
expected pattern simply produces no fact row for that type — it is never
guessed, and the value is still available through normal text retrieval.
Implemented fact types (initial set, extending the list in
`docs/knowledge-ingestion.md` is cheap since it's pure Python, no schema
change per fact type): `ENGINE_OIL_CAPACITY`,
`ENGINE_OIL_CAPACITY_WITH_FILTER`, `ENGINE_OIL_INTERVAL_KM`,
`OIL_FILTER_INTERVAL_KM`, `FRONT_TIRE_PRESSURE_KPA`,
`REAR_TIRE_PRESSURE_KPA`, `CHAIN_SLACK_MM`, `REAR_AXLE_TORQUE_NM`.

`ChatOrchestrationService` (motorcycle variant) fetches the selected
bike's facts and injects them into the system context as a small labeled
block *in addition to* retrieved text evidence, so the model has an exact
number to reference for the fact types we trust structurally, while
everything else still goes through normal retrieval. The model is
instructed to prefer an injected fact verbatim over paraphrasing a
retrieved passage when both cover the same value, and never asked to
invent one.

## 5. Chat orchestration for motorcycles

New package `dev.repair.api.motochat` (parallel to `chat`), same shape as
the car orchestration service:

`MotoRetrievalService.hybridSearch(modelId, year, queryText, queryEmbedding, limit)`
→ hard-filtered as above, RRF-fused, same as today's `RetrievalService`.

`MotoChatOrchestrationService.handleUserMessage(sessionId, garageVehicleId, userText)`:
1. Loads the garage vehicle (ownership-checked against `VisitorContext`),
   its model/year, current odometer, and a *bounded, relevant* context
   slice — not the whole garage: last maintenance event per service type
   that's plausibly relevant, not preferences unless the question is
   about setup (kept simple: always include last 5 maintenance events +
   odometer; preferences are injected only when the question text matches
   a preference-related keyword set, mirroring spec section 30's
   "don't dump everything" instruction without building a full intent
   classifier).
2. Builds the retrieval query from recent user turns (same
   history-window approach as the car service).
3. Runs `MotoRetrievalService.hybridSearch` hard-filtered to the garage
   vehicle's `(model_id, year)`.
4. Builds the evidence block + facts block + a compact "what I already
   know about this bike" block (odometer, recent maintenance,
   preferences-if-relevant) and calls `OpenAiClient.generateStructured`
   against a new `MotoDiagnosticResponseSchema`.
5. Validates citations exactly like the car path (`sourceChunkIds`/
   `evidenceChunkIds` filtered against what was actually retrieved this
   turn — CLAUDE.md's non-negotiable, unchanged).

### Controlled AI actions (schema-level, not open tool-calling)

The car prototype's OpenAI integration is single-shot structured output
(no multi-turn tool-calling loop). Building a full function-calling loop
for this pivot would be a materially larger, riskier change to
`OpenAiClient`. Instead, `MotoDiagnosticResponseSchema` extends the answer
schema with three **optional** proposed-action objects, populated by the
model only when the user has clearly and non-hypothetically stated the
fact ("I don't know" or "what if I were at 30,000 km" must never populate
one — the system prompt states this explicitly with both examples from
the spec):

```
proposedMaintenanceEvents: [{ serviceType, odometerKm?, performedAt?, notes?, intent, isCorrection }]
proposedOdometerUpdate?: { odometerKm }
proposedPreference?: { preferenceType, context, data }
```

(`proposedMaintenanceEvents` is a list, not a single optional object — a rider
can confirm more than one distinct maintenance action in one message, e.g.
"I changed the oil and oil filter at 24,000 km", and each gets its own
entry. See `docs/maintenance-tracking.md` for the current, actively
maintained description of this and the correction/relative-mileage
semantics — this section predates those and is kept as historical
architecture-decision context.)

The backend never executes these directly. `MotoChatOrchestrationService`
validates each proposal against real constraints before calling a
controlled service method — this *is* the "expose controlled backend
actions" requirement, just invoked from the orchestration layer right
after a validated model response instead of via a mid-generation tool
call:

- `serviceType` must be in the controlled taxonomy (else the whole
  proposal is dropped, not defaulted to `OTHER` silently).
- `odometerKm`, if present, must be `>= garage_vehicle.current_odometer_km
  - grace window` (small negative grace to tolerate imprecise user
  recall of a past event) and a sane upper bound (`< 500_000`) — rejects
  nonsense before it reaches the database.
- A maintenance event with an odometer lower than the *current* odometer
  is accepted (it's a past event), but a *current odometer update* lower
  than the existing value requires the number to also appear explicitly
  in the user's own message text this turn (simple substring/number
  check) — cheap guard against the model silently lowering mileage from a
  misread.
- `garageVehicleId` is always the session's own vehicle — never taken
  from model output — so there is no path for the model to write to a
  vehicle it wasn't given.

On successful validation, the service performs the write via
`GarageVehicleRepository.updateOdometer` /
`MaintenanceRepository.createEvent` / `PreferenceRepository.upsert`
(plain parameterized JDBC, never model-generated SQL) and the API
response includes a small `actionsTaken` list (e.g. `{type:
"maintenance_event_created", serviceType: "ENGINE_OIL_CHANGE",
odometerKm: 19000}`) that the frontend renders as the "Oil change saved ·
19,000 km" toast from spec section 24. An uncertain statement ("I think
the previous owner changed it around 19,000") is exactly the case the
system prompt tells the model to leave `proposedMaintenanceEvent` unset
and instead ask a follow-up/clarification — matching spec section 24's
worked example.

This is a deliberate, documented simplification versus a full
multi-action tool loop; it satisfies every example conversation in the
spec (oil change confirmation, odometer statement vs. hypothetical,
chain-lube follow-up) without speculative infrastructure for actions nver
demonstrated by the spec's own examples (no delete/undo action is asked
for anywhere in the brief).

## 6. Maintenance status computation

Pure, deterministic Java (`MaintenanceStatusService`, no LLM involved):
for each service type with a known interval from `motorcycle_facts` (or
absent, if not extractable — then the dashboard shows "Not recorded" and
no status), compute against the vehicle's current odometer and the latest
matching `maintenance_events` row:

```
distanceSince = currentOdometer - lastEvent.odometerKm   (if both known)
remaining     = intervalKm - distanceSince
status:
  UNKNOWN      no interval fact, or no last event and no way to estimate
  OK           remaining > 20% of interval
  DUE_SOON     0 < remaining <= 20% of interval
  DUE          remaining <= 0 and overdue by <= 20% of interval
  OVERDUE      overdue by more than 20% of interval
```

Time-based intervals (e.g. brake fluid, "every N months") use
`performed_at` + calendar months the same way, and when both a distance
and a time interval exist for the same service type, whichever produces
the more urgent status wins (spec section 19: "whichever comes first").
This logic is unit-tested directly (section 8) against the worked
examples in the spec (23,500 km / 6,000 km interval / 19,000 km last
service ⇒ ~1,500 km remaining, not overdue; 25,400 km ⇒ slightly overdue).

## 7. APIs added

```
GET    /api/motorcycles/manufacturers
GET    /api/motorcycles/manufacturers/{id}/models
GET    /api/motorcycles/models/{id}/years

POST   /api/garage/vehicles                body: {modelId, year, nickname?}
GET    /api/garage/vehicles
GET    /api/garage/vehicles/{id}
PATCH  /api/garage/vehicles/{id}            body: {nickname?, currentOdometerKm?}
DELETE /api/garage/vehicles/{id}

GET    /api/garage/vehicles/{id}/maintenance
POST   /api/garage/vehicles/{id}/maintenance body: {serviceType, odometerKm?, performedAt?, notes?}
GET    /api/garage/vehicles/{id}/dashboard   -> per-service-type status cards

GET    /api/garage/vehicles/{id}/preferences
POST   /api/garage/vehicles/{id}/preferences body: {preferenceType, context, data}

POST   /api/moto-sessions                   body: {garageVehicleId}
GET    /api/moto-sessions
GET    /api/moto-sessions/{id}
DELETE /api/moto-sessions/{id}
POST   /api/moto-sessions/{id}/messages     body: {content}
GET    /api/moto-rag-runs/{requestId}        (debug/evidence, same shape as car)
```

All ownership-checked against `VisitorContext` at the SQL layer, same
pattern as the car endpoints.

## 8. Testing strategy

- **Python**: frontmatter validation (missing required field raises),
  chunk category derivation, idempotent re-ingestion (same hash ⇒ no
  writes), content-change re-ingestion (chunks replaced, not duplicated),
  year-range containment logic, alias upsert without cross-model merge,
  deterministic fact extraction against the real Yamaha files, and the
  **extensibility test**: a `testbrand/test-bike/2025.md` fixture ingests
  cleanly and produces a manufacturer/model/year with zero production
  code changes, then is cleaned up (not left behind in dev DB state after
  the test run).
- **Java**: `MotoRetrievalServiceIT`/`MotoChatOrchestrationServiceTest`
  contamination tests against the real ingested Yamaha corpus — selecting
  MT-07 2025 and asking about the chain must never return an MT-09 chunk;
  selecting MT-09 SP must not silently fall back to base MT-09 suspension
  text; selecting Ténéré 700 (standard) must not surface World Raid
  content. `MaintenanceStatusServiceTest` covers the worked numeric
  examples from spec sections 18/19/51 (on-time, overdue, missing data,
  date-based, combined date+distance). `GarageVehicleRepositoryIT`/
  `MaintenanceRepositoryIT` cover ownership isolation across two visitor
  ids and across two garage vehicles for the same visitor.
- **Frontend**: `apps/web` production build (`next build`) as a
  compile-correctness gate; existing Playwright golden-path adapted to
  the new bike-selection → chat flow if time allows within this session,
  documented as a known limitation otherwise.

## 9. Frontend structure

```
/                         landing — motorcycle positioning, "Choose your bike" CTA
/chat                     bike picker (if none selected) or empty-state chat
/chat/[sessionId]         chat thread, header shows "Yamaha MT-07 · 2025" + Change bike
/garage                   My Garage — list of garage vehicles, + Add a bike
/garage/[id]              maintenance dashboard for one vehicle
```

`components/BikePicker.tsx` replaces `VehiclePicker.tsx` on the new
routes (manufacturer → model → year Combobox chain against
`/api/motorcycles/*`); `VehiclePicker.tsx` and `/quality` stay in the tree
unreferenced from primary navigation (car legacy, isolated per section 1
above — not deleted, since the demo car flow is still reachable by direct
URL as a preserved historical artifact, matching "the previous car-based
prototype is intentionally preserved").

Visual identity (navy hero, white chat panel, `Combobox`, evidence
drawer, Tailwind tokens in `globals.css`) is kept unchanged; only copy,
iconography, and the picker/dashboard components are new.
