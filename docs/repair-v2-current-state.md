# Repair V2 — current-state audit

Audit performed on branch `repair-v2-motorcycles` before starting the
car → motorcycle pivot. This documents what exists today, what's reusable
as-is, what's car-specific legacy, and what needs to be built.

## Repository shape

```
apps/api/         Java 21 / Spring Boot 4.1, JDBC (JdbcClient, no JPA), Flyway
apps/web/         Next.js 16 / React 19 / Tailwind 4
pipelines/        Python 3.11 ingestion CLI (catalogue import + knowledge embeddings)
data/raw/         private car CSV + legacy SQL dump (git-ignored)
data/fixtures/    small public car fixtures for tests
data/knowledge/   car knowledge corpus (Markdown, car-specific frontmatter schema)
knowledge/motorcycles/  NEW motorcycle knowledge corpus (Markdown), committed,
                        not yet wired to any ingestion pipeline or schema
docker-compose.yml  Postgres 17 + pgvector on host port 5544 (own volume,
                     never the owner's local Postgres)
```

The Java and web apps, and the whole `apps/api`/`apps/web` tree, were
committed in one shot (`24d7186`) as an already-working car prototype —
there is no earlier car-specific history to preserve separately from this.
The most recent commit (`34a6d03`) added 29 motorcycle Markdown files
under `knowledge/motorcycles/yamaha/**` with **zero** wiring: no ingestion
code, no DB schema, no API, no frontend references it.

DB (`repair_v2` on `localhost:5544`) is up and already has the car
catalogue imported (18 tables, Flyway at V5). A real `OPENAI_API_KEY` is
configured in `.env` (verified present, not printed).

## What the car prototype already does well (reusable patterns)

- **Ownership isolation baked into SQL.** `VisitorCookieInterceptor` sets
  an anonymous UUID cookie; `VisitorContext` is a request-scoped bean
  carrying it; every repository method that touches per-visitor data takes
  `visitorId` as a SQL parameter — there is no "read by id, check owner in
  Java" pattern anywhere. This is exactly the ownership model the
  motorcycle spec asks for (section 45/46) and needs no changes.
- **Hybrid RAG** (`RetrievalService`): pgvector cosine top-20 + Postgres
  full-text top-20, combined with reciprocal rank fusion (k=60), each leg
  independently scoped by a vehicle filter *before* ranking (not after).
  This is the right shape for the motorcycle hard-filtering requirement
  (section 12/13) — it needs a motorcycle-specific vehicle filter (model +
  year range) instead of the car `variant_id`/`model_id` filter, but the
  fusion/ranking code is directly reusable in spirit.
- **Structured, schema-validated generation** (`OpenAiClient` +
  `DiagnosticResponseSchema` + `ChatOrchestrationService`): calls the
  OpenAI Responses API with `text.format.json_schema` + `strict: true`, so
  the model physically cannot emit a field or citation shape the app
  doesn't expect. `validateCitations` then re-filters `evidenceChunkIds`/
  `sourceChunkIds` against the chunk ids that were *actually retrieved
  this turn* — never trusts the model's own claim of what it cited. This
  is precisely the pattern CLAUDE.md's non-negotiable about citation
  validation requires, and precisely the pattern to keep for motorcycles.
- **`rag_runs` / `retrieved_evidence` debug trail**: every orchestration
  attempt (success or failure) is logged with retrieval filters, per-leg
  scores, fused rank, and provider status, keyed by a client-visible
  `request_id`. `GET /api/rag-runs/{requestId}` (ownership-checked) backs
  the Evidence & Debug drawer. Reusable pattern for motorcycles.
- **Idempotent Markdown ingestion** (`pipelines/embeddings/ingest_knowledge.py`
  + `chunker.py`): content-hash-gated re-ingestion (skip unchanged
  documents, replace chunks on change), heading-aware chunking (`##`/`###`
  boundaries, word-budget fallback for long sections) rather than
  fixed-size windows. The chunker (`chunk_markdown_body`) is generic
  Markdown logic with no car-specific assumptions — reused as-is for
  motorcycles. The surrounding ingestion driver *is* car-specific (its
  frontmatter schema — `provenance`, `evidence_scope`, `applies_to_model`
  — doesn't match the motorcycle files' frontmatter at all) and needs a
  parallel motorcycle-specific driver, not a shared one.
- **Graceful OpenAI degradation**: missing key, timeout, rate limit, and
  malformed-output are all distinct `provider_status` values surfaced to
  the user as plain language, never a 500. Reused as-is.
- **Visual identity**: dark navy hero + white panel chat shell, `Combobox`
  searchable dropdown, sidebar session list, evidence drawer, Tailwind 4
  CSS variables in `globals.css` (`--navy`, `--accent`, `--panel`, etc).
  Kept; only copy/content changes.

## What is car-specific and being isolated, not deleted

Per CLAUDE.md and the task brief, the car prototype is a preserved
historical checkpoint, not something to delete. Concretely, *isolated*
means: left in place, compiling, covered by its existing tests, but no
longer reachable from the primary navigation or from any new motorcycle
code path.

- **Schema** (`V1`–`V5` migrations): `manufacturers`, `vehicle_models`,
  `vehicle_variants`, `vehicle_specs`, `import_runs`,
  `raw_vehicle_records`, `manufacturer_source_map`, `data_quality_issues`,
  `documents`/`document_chunks`/`document_vehicle_links`/`document_assets`,
  `diagnostic_sessions`/`diagnostic_messages`/`rag_runs`/`retrieved_evidence`.
  All left untouched. New motorcycle tables are added in new migrations
  (`V6+`) under new names — no shared table is repurposed, so the car
  catalogue keeps working exactly as today.
- **Java**: `catalogue/*`, `chat/*` (the car `ChatOrchestrationService`,
  `RetrievalService`, `DiagnosticResponseSchema`), `dataquality/*`,
  `conversation/*` (car sessions keyed by `variant_id`) are left as-is.
  New `garage/`, `motorcycle/`, and a parallel `motochat` (or similarly
  named) package are added alongside them.
- **Python**: `pipelines/ingest/*` (CSV + legacy SQL catalogue importer)
  and `pipelines/embeddings/ingest_knowledge.py` (car knowledge ingestion)
  are untouched. A new `pipelines/embeddings/ingest_motorcycle_knowledge.py`
  (name indicative) is added alongside, reusing `chunker.py`.
  `data/raw/`, `data/knowledge/`, `data/fixtures/` stay exactly as they
  are — private inputs, never touched by the motorcycle work.
- **Frontend**: the car `VehiclePicker`, `/quality` data-quality page, and
  the car system prompt/example questions are either replaced at the
  route level or left as dead code behind the old route. The primary `/`
  and `/chat` experience becomes the motorcycle product; nothing under
  `apps/web` needs to be deleted for that to be true.

## Gaps to close (this is the actual scope of the pivot)

1. No DB schema at all for: motorcycle catalog (manufacturer/model/year
   coverage derived from knowledge documents), motorcycle knowledge
   documents/chunks/facts, garage vehicles, maintenance events, vehicle
   preferences.
2. No ingestion pipeline for `knowledge/motorcycles/**/*.md` (different
   frontmatter shape than the car corpus: `manufacturer`, `model`,
   `year_from`, `year_to`, `market`, `aliases`, `last_verified` — no
   `provenance`/`evidence_scope`/`applies_to_*`).
3. No dynamic catalog API (manufacturers/models/years derived from what's
   actually been ingested, not a hardcoded list).
4. No garage vehicle concept, no maintenance history, no preferences.
5. No motorcycle-specific chat orchestration (bike-aware system prompt,
   hard model+year-range filtering, deterministic facts layer, controlled
   maintenance-event/odometer/preference actions).
6. No motorcycle frontend: bike picker (manufacturer → model → year), My
   Garage, maintenance dashboard, "currently selected bike" chat header.
7. No regression/evaluation tests proving cross-model contamination is
   impossible (MT-07 query must never surface MT-09 chunks, etc.) or that
   a brand-new manufacturer requires zero source changes.

See `docs/repair-v2-architecture.md` for the design that closes these gaps.
