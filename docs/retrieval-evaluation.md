# Retrieval evaluation

Automated regression coverage proving the hard vehicle filter actually
prevents cross-model/cross-year contamination, plus a record of the live
manual verification performed during the motorcycle pivot.

## Retrieval evaluation set

`apps/api/src/test/java/dev/repair/api/motochat/MotoRetrievalEvaluationIT.java`
runs a fixed question set against the real curated corpus in
`knowledge/motorcycles/`. It makes retrieval changes measurable and catches
regressions.

**Setup.** The test chunks every knowledge file the way the ingestion
pipeline does (`heading-v1`, a Java port of `pipelines/embeddings/chunker.py`),
loads the chunks into a Testcontainers Postgres without embeddings and calls
`MotoRetrievalService.hybridSearch` for each question. No network or OpenAI
call is made. Because chunks have no embedding, the vector pool is empty and
the run measures the lexical (full-text) half of hybrid search. Vector
quality is not covered by this test.

**Dataset.** `apps/api/src/test/resources/retrieval/retrieval-eval-v1.json`
(version 1) has 40 questions across 11 models (MT-07, MT-09, MT-09 SP,
MT-10, MT-03, Ténéré 700, Ténéré 700 World Raid, Tracer 9, XSR900, YZF-R6,
YZF-R7) and model years 2006–2026. Each case has:

| Field | Meaning |
|---|---|
| `id` | Unique, stable identifier |
| `model` | Model directory slug under `knowledge/motorcycles/yamaha/` |
| `year` | Model year the rider selected |
| `question` | The rider's question, for readers |
| `query` | Keywords sent to retrieval. Full-text search requires every term to occur in a chunk, so these are chosen to be present in the expected section |
| `expectedSection` | Level-2 heading of the section that must be retrieved |

A separate test checks that the dataset has at least 30 cases, 6 models and 3
years, that ids are unique, and that every case points to an existing corpus
document and section, so editing the knowledge files cannot silently
invalidate the set.

**Metrics** (printed as a `RETRIEVAL-EVAL` line, followed by one line per miss):

- **Isolation**: share of questions whose results (up to 20 chunks) all come
  from the selected model and from a document whose year range covers the
  selected year. Threshold: `1.0`. Any violation fails the test and is listed.
- **Hit rate at k**: share of questions where a chunk of the expected section
  is among the top `k = 5` results. Threshold: `0.875`.
- **MRR**: mean reciprocal rank of the expected section. Reported only, not
  asserted.

**Scope.** This is a lexical-only evaluation. It uses the curated `query`
keywords, not the rider's `question` and not the retrieval query that chat
builds from conversation history, and it does not exercise embeddings or the
vector half of the fusion. Treat hit@5 as an optimistic upper bound for
keyword retrieval, not as a measure of end-to-end answer quality.

**Baseline (v1, first CI run, Testcontainers Postgres).**

| Metric | Value |
|---|---|
| Questions | 40 |
| Isolation | 1.000 |
| hit@5 | 0.900 (36/40) |
| MRR | 0.686 |

Missed cases (expected section not in the top 20 results):

| Case | Expected section |
|---|---|
| `mt07-2022-oil-level` | Engine oil |
| `mt07-2022-drain-torque` | Oil filter and drain fasteners |
| `mt07-2018-chain-lube` | Drive chain |
| `tenere-2021-oil` | Engine oil |

Every query term occurs somewhere in the expected section, so these misses
are not explained by missing vocabulary. Possible causes are terms split
across chunk boundaries or ranking against other sections; they have not been
investigated and are kept in the set rather than edited to raise the score.

**Text query change (RP-GEN-008).** The text pool used `plainto_tsquery`, which
requires every word of the query to occur in one chunk. It now builds an OR
`to_tsquery('english', ...)` from the letter/digit terms of the query (distinct,
at least 2 characters, at most 24, stop words dropped by the `english`
configuration), so a chunk matching a meaningful subset of the terms is a
candidate and `ts_rank` orders chunks by how well they match. Terms are
extracted as plain word runs, so no tsquery operators can come from user text.
The model and year filter and the indexed `to_tsvector('english', c.content)`
expression are unchanged. Empty, punctuation-only or stop-word-only queries
return no text candidates without an error.

| Metric | Before (plainto_tsquery) | After (OR query) |
|---|---|---|
| Isolation | 1.000 | not yet measured |
| hit@5 | 0.900 (36/40) | not yet measured |
| MRR | 0.686 | not yet measured |

The "after" column was not measured in the authoring environment because the
evaluation needs Docker (Testcontainers). Record the `RETRIEVAL-EVAL` line from
the first CI run here and keep hit@5 at or above the "before" value; if it
drops, the ranking needs work rather than the threshold.

**Thresholds.** They are the constants `MIN_ISOLATION` and `MIN_HIT_RATE_AT_K`
in the test. Isolation must stay at 1.0. The hit-rate threshold is `0.875`
(35/40), one case below the CI baseline. Never lower a threshold to make a
regression pass. Fix the retrieval change or justify the new value in the
pull request. Raise it when misses are fixed.

**Extending the set.** Add an object to `retrieval-eval-v1.json`. Pick a
section that exists in that model-year file, write a `query` whose terms all
occur in the section body (headings are not part of the chunk content), run
the test and read the printed rank. Cover new models and years first. For a
change to the question semantics or chunking, add a new versioned file
instead of editing v1, so results stay comparable.

## Automated tests

`apps/api/src/test/java/dev/repair/api/motochat/MotoRetrievalServiceIT.java`
(Testcontainers Postgres, real hybrid search, no mocks):

- **`chainQueryOnMt07NeverReturnsAnMt09Chunk`** — seeds near-identical
  chain-maintenance chunks for MT-07 and MT-09 under the same
  manufacturer, queries with MT-07 selected, asserts zero MT-09 content
  in the results (not just lower-ranked — structurally excluded from the
  candidate pool).
- **`mt09SpSuspensionIsNotReplacedByStandardMt09Suspension`** — proves an
  MT-09 SP selection never falls back to base-MT-09 suspension content
  even though "MT-09" is a literal substring of "MT-09 SP" — model
  identity is exact-match, never prefix/fuzzy.
- **`documentOutsideItsYearRangeDoesNotMatch`** — a document with
  `year_from=2021, year_to=2023` matches a 2022 query and returns nothing
  for a 2024 query on the same model.
- **`longConversationalQuestionMatchesUnderTolerantQuery`** — a long,
  wordy question matches a chunk that contains only some of its words.
- **`tolerantQueryStillRespectsModelAndYearFilter`** — the OR query returns
  nothing from another model or a document outside the year range.
- **`emptyOrPunctuationOnlyQueryReturnsNoTextCandidates`** — blank,
  punctuation-only, stop-word-only and null queries return no results and no error.

`MotoRetrievalQueryTest` unit-tests the term extraction (no database).

`apps/api/src/test/java/dev/repair/api/garage/MaintenanceStatusServiceTest.java`
covers the numeric worked examples (see `docs/maintenance-tracking.md`).

`pipelines/tests/test_ingest_motorcycle_knowledge_integration.py::test_distinct_models_are_never_merged_by_alias`
covers the same contamination concern at the ingestion layer: MT-09 and
MT-09 SP always resolve to two distinct `motorcycle_models` rows.

Run everything: `cd apps/api && ./mvnw test` (full suite, car + moto,
passed as of this writing) and `cd pipelines && python -m pytest` (48
tests, all passing).

## Live manual verification (this session)

Performed against the real ingested Yamaha corpus (29 documents, 895
chunks, 588 facts) with a real `OPENAI_API_KEY`, `gpt-4.1-mini` /
`text-embedding-3-small`, through the actual running API and a real
browser session — not simulated:

1. **Yamaha MT-07 2025, "When should I lubricate the chain?"** — answer
   grounded in the MT-07 2025 document only (`documentId=95` across all 6
   evidence chunks), natural conversational phrasing (not a Markdown
   dump), correct 1,000 km / after-rain interval, relevant follow-up
   questions.
2. **Conversational maintenance capture** — "I actually just lubricated
   the chain today at 15000 km" produced both a
   `maintenance_event_created` (CHAIN_LUBE, 15,000 km) and an
   `odometer_updated` (15,000 km) action, verified persisted via
   `GET /api/garage/vehicles/1/maintenance` and the vehicle's
   `currentOdometerKm`.
3. **Hypothetical guard** — "What maintenance would I need if I were at
   30,000 km?" produced a real answer but **zero** actions; the stored
   odometer stayed at 15,000, confirmed via a follow-up `GET`.
4. **Yamaha MT-09 SP 2025, "What is the rear suspension spring preload
   setting?"** — every one of the 6 retrieved evidence chunks' section
   paths began with `Yamaha MT-09 SP 2025 >`; none from base MT-09.
5. **Full browser UI flow**: landing page → BikePicker (Yamaha / Tenere
   700 / 2025) → garage vehicle + chat session auto-created → live
   multi-turn conversation ("When is my next oil change?" → clarifying
   question with bike-specific facts shown → "I last changed it at
   19,000 km and I'm currently at 23,500 km" → correct answer: "~1,500 km
   remaining" → follow-up "When was the last oil change date?" → model
   correctly asked for the date, having retained "it" = oil change from
   context) → Evidence & Debug drawer confirmed `Bike: Yamaha / Tenere
   700 / 2025` and all 6 retrieved chunks under that same model/year.
6. **Garage dashboard**: manually set odometer to 23,500 km, recorded an
   `ENGINE_OIL_CHANGE` at 19,000 km via the "+ Record maintenance" form,
   dashboard immediately showed `Engine oil: OK — Last: 19,000 km, 1,500
   km remaining` — matching the spec's own worked example exactly — while
   every other service type honestly showed `Unknown` / "Not recorded" /
   "No verified interval for this service on this bike".

Total OpenAI spend for the full ingestion + live verification session:
≈$0.02 (ingestion ≈$0.0014, chat/embedding calls a few cents at
`gpt-4.1-mini` rates) — not separately itemized per call, but bounded by
the existing per-turn evidence-word and output-token caps
(`ChatProperties`).

## Live manual verification — QA/correctness pass (2026-09-14)

Performed against the real running app (real `OPENAI_API_KEY`,
`gpt-4.1-mini`) after the action-confidence and dashboard fixes, directly
reproducing the manual QA scenarios that motivated the pass:

1. **Uncertain history never writes.** "When is my next oil change?" →
   clarifying question; then "I think the previous owner changed the oil
   at 12,000 km, but I'm not sure." → the model itself asked "Do you want
   me to record that as a confirmed oil change, or is it only an
   estimate?", `actionsTaken: []`, zero rows in `maintenance_events`.
2. **Confirmed maintenance persists.** "I changed the oil myself
   yesterday at 18,450 km." → `maintenance_event_created` action, real
   DB row (`ENGINE_OIL_CHANGE`, 18,450 km, `created_via='chat'`); current
   odometer correctly left untouched (the statement was about a past
   event, not current mileage).
3. **Hypothetical is read-only — the exact reported bug.** Odometer set
   to 18,450 km; "If I were at 25,000 km, what maintenance would be
   due?" → useful, correct hypothetical answer (oil/filter/plugs due
   near 24,450–25,000 km, air filter/valve clearance much later),
   `actionsTaken: []`, odometer confirmed still 18,450 km afterward, no
   new `maintenance_events` row at 25,000 km.
4. **Dashboard reflects known intervals without history.** Fresh
   garage vehicle's `/dashboard` response: `SPARK_PLUG_CHANGE`,
   `OIL_FILTER_CHANGE`, `CHAIN_LUBE`, `AIR_FILTER_CHANGE`,
   `VALVE_CLEARANCE_CHECK`, `COOLANT_CHANGE`, `BRAKE_FLUID_CHANGE` all
   returned `INTERVAL_KNOWN_NO_HISTORY` with real interval values (e.g.
   `AIR_FILTER_CHANGE`: interval 37,000 km, remaining 18,550 km at a
   18,450 km odometer); `CHAIN_ADJUSTMENT`/`TIRE_REPLACEMENT`/
   `BATTERY_REPLACEMENT` honestly stayed `UNKNOWN`. Confirmed visually in
   the browser (blue "Interval known" badges vs. gray "Unknown").
5. **Missing exact spec still refused.** "What is the exact torque for
   the camshaft bearing cap bolts?" → `insufficient_evidence`,
   `sourceChunkIds: []`, no invented number.
6. **Deterministic unit conversion, no clarification round-trip.** After
   "What tire pressure should I use?" (250 kPa front/rear), "instead of
   kPa, bar" → "...250 kPa..., which converts to 2.50 bar," zero
   follow-up questions, no re-asking which pressure was meant.
7. **Garage reuse vs. explicit duplicate.** Three consecutive normal
   selections of the same manufacturer/model/year returned the same
   garage vehicle id (`200 OK` after the first `201 Created`); the
   garage's own vehicle count never grew. `allowDuplicate: true` (the
   Garage page's own "+ Add another bike") created a genuinely new row.
   Reconfirmed in the browser: selecting an already-owned bike through
   "+ New chat" opened a new *conversation* but did not add a new bike to
   My Garage.
8. **Context relevance.** After recording an unrelated chain-lube event,
   "I feel the bike is hotter than usual" → answer referenced coolant
   capacity/interval and engine oil, `contextUsed` contained only the
   current odometer — no chain-lubrication history leaked into the
   response.
9. **Provenance separation, visually confirmed in the browser.** A live
   answer rendered two distinct sections: "Verified bike facts" (green —
   "Engine oil change interval: 6,000 km or 6 months") and "Your bike"
   (gray — "Engine Oil Change at 18450 km"), plus exactly one follow-up
   question.

All nine scenarios passed as specified. This is qualitative,
example-based verification (the same honest caveat as the previous live
verification round) — not a statistical evaluation — but it directly
reproduces the originally-reported bug scenarios with a real model, not a
mock.

## Known gap

No automated Playwright end-to-end test was added for the motorcycle
flow in this session (the existing car-flow specs under `apps/web/tests/`
were left as-is, covering the preserved car prototype route). The
production build (`next build`) passes as a compile-correctness gate, and
the flow above was verified manually through a real browser session
end-to-end, but there is no CI-enforced regression test for the frontend
motorcycle flow yet — see the final engineering report for this as a
named limitation.
