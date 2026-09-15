# Retrieval evaluation

Automated regression coverage proving the hard vehicle filter actually
prevents cross-model/cross-year contamination, plus a record of the live
manual verification performed during the motorcycle pivot.

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
