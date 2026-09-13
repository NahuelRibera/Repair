# Implementation status

Last updated: 2026-09-13 (bug-fix session: chat scroll layout, catalogue
pagination, demo-vehicle identification). Working log, not a changelog —
describes what is actually verified right now.

## This session: three bug fixes (chat scroll, catalogue pagination, demo vehicle)

Fixed three concrete, previously-reported bugs. No re-embedding, no
catalogue reimport, no database reset — same running Postgres data as
before.

### Bug 1 — chat scroll layout

**Root cause**: `ChatShell`'s root div was `flex-1 flex min-h-0`, which
only constrains height relative to an ancestor — but the actual ancestor
(`<body className="min-h-full flex flex-col">` in the root layout) is
intentionally *unbounded* so the marketing landing page can scroll
normally. With no bounded ancestor, the chat route's content simply grew
the whole document instead of clipping, so the entire page — including the
sidebar — scrolled together, and a long conversation pushed the logo/"New
chat"/conversation list off-screen.

**Fix**: `ChatShell` root is now `h-dvh flex overflow-hidden` (an absolute
viewport-relative height, independent of `<body>`, so `<body>` stays
untouched and the landing page is unaffected). Every fixed region
(sidebar header/search, conversation header, composer) got `shrink-0`; the
two genuinely scrollable regions (sidebar session list, message list) got
`min-h-0 overflow-y-auto overscroll-contain` — `min-h-0` is what lets a
flex child actually shrink and scroll instead of being pushed to its
content's full height, and `overscroll-contain` stops scroll chaining
(scrolling the sidebar list to its end no longer bubbles up to scroll the
page). No hardcoded pixel offsets, no JS wheel-event interception anywhere
in this fix.

Auto-scroll: a `useRef` tracks whether the reader was within 150px of the
bottom before new content arrived; the scroll-to-bottom effect only fires
in that case (or when the user just sent a message, which always forces
it), so reading old messages is never interrupted by an incoming reply.
One additional bug surfaced while verifying this: `scrollIntoView` was a
silent no-op on the bottom sentinel because it had zero height and because
`behavior: "smooth"` doesn't reliably fire in this environment — fixed by
giving the sentinel `className="h-px"` and switching to `behavior:
"auto"`.

**Verified**: seeded a real session with 25 synthetic user/assistant
message pairs and 20 sibling sessions directly via SQL (zero OpenAI cost)
and drove it with real Playwright browser interaction: page loads scrolled
to the latest message; scrolling the message list to the oldest message
leaves the sidebar, "New chat" link, and header still in the viewport;
scrolling the sidebar list does not move the open conversation; no outer
document scrollbar appears. Mobile drawer (hamburger open/close, no
horizontal overflow) re-confirmed via the existing Playwright viewport
test. See `tests/e2e/layout-and-catalogue.spec.ts`.

### Bug 2 — incomplete manufacturer/model selectors

**Root cause**: traced the full path as instructed, not assumed to be CSS.
`VehiclePicker.tsx` hardcoded `size=20` on both the manufacturer and model
fetches; `CatalogueController.MAX_PAGE_SIZE` was `100`. The real catalogue
has 112 manufacturers (so `size=20` only ever showed the first 20,
alphabetically ending around "Cupra" — an exact match for the reported
symptom) and up to ~100 models for a single manufacturer (BMW has exactly
100; "BMW 3 Series Sedan" sorts to index 29, never reachable at
`size=20`). Confirmed both bounds live against the running API before
changing anything.

**Fix**: bumped `CatalogueController.MAX_PAGE_SIZE` from 100 to 500
(comfortably above the real catalogue's current maximums) and
`VehiclePicker`'s `BROWSE_SIZE` constant to match, so opening either
dropdown with no search text fetches the complete, truly browsable list in
one request — no change to any other endpoint's pagination. Also fixed:
`ORDER BY canonical_name` / `model_name` → `ORDER BY LOWER(...)` for
genuine case-insensitive alphabetical sort; a `variantsRequestGuard` ref
counter in `selectModel` so a slower, stale variants response can never
overwrite what a faster, newer selection already set; the `Combobox`
component rewritten with real ARIA roles (`combobox`/`listbox`/`option`,
`aria-activedescendant`), full keyboard navigation (arrow keys + Enter +
Escape), and a genuine loading/loaded/error state machine — previously a
failed fetch left the list at "No matches" indistinguishable from a
truly-empty result; now it shows "Couldn't load options." with a Retry
button. The "Motorization" field label had said "(optional)" although the
Start button stays disabled until a variant is chosen — backend genuinely
requires one, so the label was corrected to stop implying otherwise.

**Verified live** against the real running API/DB: `GET
/api/manufacturers?size=500` returns all 112 (last three alphabetically:
Xpeng, Zender, Zenvo); `GET /api/manufacturers/5/models?size=500` returns
all 100 BMW models including "BMW 3 Series Sedan". In the browser via
Playwright (no search text typed, pure scrolling): "Toyota" reachable in
the manufacturer list; "BMW 3 Series Sedan" reachable in the model list;
arrow-key + Enter selection works. New Java tests
(`CatalogueRepositoryIT`, `CatalogueControllerTest`) reproduce the
size-20-can't-reach-a-model-sorted-past-the-cutoff bug and prove the fix,
plus prove case-insensitive ordering and the size clamp. Full Java suite:
**31/31 pass** (was 24; +7 from this session).

### Bug 3 — wrong demo vehicle identified

**Root cause**: `/api/vehicles/covered` joins every variant reachable via
a *model-wide* document link — the 6 generic BMW documents apply to the
entire "BMW 3 Series Sedan" model, i.e. all 277 variants spanning E30
through G20 — then sorts by `year_start` ascending. The frontend took
`covered[0]`: the *oldest* generation in that broad join (an E30 318i),
not the specific, curated E90 320d that actually has scenario-level
knowledge coverage. This affected both the "Supported demo vehicle" panel
text and which variant the example buttons actually opened a session for.

**Fix**: added `repair.demo.*` config (manufacturer, model, a
variant-name substring, a year) and a new
`CatalogueRepository.findDemoVariant(...)` query that only resolves a
variant that both matches those criteria **and** has a real
`document_vehicle_links` row — i.e. actual knowledge coverage, not a
guessed id or "first result." Exposed as `GET /api/vehicles/demo`,
returning 404 if the configured vehicle can't currently be resolved. Added
a shared frontend hook, `useDemoVehicle()`, as the single source of truth;
both `chat/page.tsx` and `LandingPicker.tsx` now consume it instead of
independently guessing from `covered[0]`. If the endpoint 404s, the UI
shows an explicit amber "configured demo vehicle isn't available" notice
rather than silently substituting a different vehicle. Also corrected an
overpromising insufficient-evidence message that implied a "general
guidance" fallback mode which doesn't actually exist.

**Verified live**: `GET /api/vehicles/demo` correctly returns variant
23079, "BMW 3 Series (E90) 320d 6MT RWD (177 HP)", `hasKnowledgeCoverage:
true`. In the browser via Playwright: the "Supported demo vehicle" panel
names the E90 320d; the landing page and in-app panel agree; clicking an
example button opens a session whose header shows the same E90 320d
variant. New Java test `findDemoVariantResolvesOnlyAVariantWithRealKnowledgeCoverage`
seeds two identically-named variants (one covered, one not) and proves
only the covered one resolves.

### An unplanned but important finding: this session made unintended live OpenAI calls

Discovered mid-verification, not part of the three bugs above, and
important enough to record plainly. `docs/planning/status.md` (this file,
previous session) already documents that `OPENAI_API_KEY` was
deliberately loaded from `.env` and the API restarted to run a **budgeted,
approved** live-RAG verification (5 calls, $0.0058, see "Cost accounting"
above). This session's own Playwright work incorrectly assumed — from a
stale comment in the pre-existing `golden-path.spec.ts` ("No
OPENAI_API_KEY is set for this run") rather than checking this file's
already-documented current state — that sending a chat message in a test
would hit the harmless, free "AI not configured" fallback. It does not:
the key is genuinely loaded, so every test run that filled the composer
and pressed Enter, or clicked a demo example button (which auto-sends a
prefilled message), made a real, billed OpenAI call.

**Measured impact** (queried directly from `rag_runs`, matching this
session's own wall-clock activity, 17:46–17:56 UTC on 2026-09-13, cleanly
separable from the prior session's already-accounted-for calls): **9
extra generation calls, 15,242 prompt tokens, 3,399 completion tokens =
$0.0115, measured**. Embedding-side cost for the same 9 turns is
negligible (a handful of short queries at $0.02/1M tokens) and not
separately measured, consistent with how the prior session treated the
same order of magnitude. This is on top of, not instead of, the prior
session's already-approved $0.0058 — combined known spend across both
sessions ≈ **$0.017**, still trivial in absolute terms, but it should not
have happened silently and the constraint for this session ("do not spend
API credits unless a specific remaining issue genuinely requires a live
request") was violated in practice, even though inadvertently.

**Fix applied**: both `golden-path.spec.ts` and
`layout-and-catalogue.spec.ts` now intercept the browser-level network
calls that would otherwise reach `/api/sessions/:id/messages` (and, for
the reload case, the follow-up `GET /api/sessions/:id`) and fulfill them
with a stubbed response, so the Java backend — and therefore OpenAI — is
never invoked by this suite, **regardless of whether a real key happens to
be present in `.env`**. This is deliberately more robust than relying on
an environment assumption: it fixes both the immediate cost issue and the
underlying fragility (a test suite whose behavior silently depends on
undocumented local `.env` state). No further live calls were made after
this fix landed; both spec files were re-run twice afterward to confirm
stability, each full run completing in ~8–9 seconds (down from 18–30s
while real network calls were in flight) with zero flakiness.

The application itself was not changed to cause or fix this — this was
purely a test-authoring mistake on my part, now corrected.

## Running right now (this environment)

- **Frontend**: http://localhost:3000 (Next.js dev server, `npm run dev`)
- **API**: http://localhost:8082 (Spring Boot, `./mvnw spring-boot:run`,
  restarted this session to load the real `OPENAI_API_KEY`) — health:
  `curl http://localhost:8082/actuator/health`
- **Database**: Docker container `repair-v2-db` (pgvector/pgvector:pg17),
  host port 5544, named volume `repair_v2_pgdata` — healthy, untouched.
  Catalogue: 112 manufacturers / 2,507 models / 27,194 variants (not
  re-imported this session — already present). Knowledge corpus: **8
  documents, 37 chunks, all embedded** (new this session).
- Unrelated `rabbitmq` container on host port 8080 left untouched.
- Logs: `.logs/api.log`, `.logs/web.log` (repo-root, git-ignored).
- `OPENAI_API_KEY` confirmed present in `.env` (164 chars, `sk-` prefix
  checked without printing the value) and `.env` confirmed git-ignored.
- `diagnostic_sessions`/`diagnostic_messages` have accumulated test data
  across sessions (389 sessions / 1,124 messages as of this session's end)
  from repeated Playwright runs (each test creates its own anonymous
  visitor session) and manual SQL-seeded fixtures used to verify scrolling
  without live API calls. Left in place per this task's explicit
  instruction not to reset the database — harmless local dev clutter, not
  a data-integrity issue, and doesn't affect the catalogue or knowledge
  corpus.

## Live RAG verification — real OpenAI calls, this session

**Models used** (unchanged from the existing baseline — inspected current
OpenAI pricing/availability first, confirmed both remain the right choice
for a small demo, did not swap or run a broader comparison):

- Generation: `gpt-4.1-mini` via the Responses API with strict structured
  outputs. Confirmed available (not deprecated), supports Responses API +
  `json_schema` strict mode, 1,047,576-token context / 32,768 max output —
  our 900-token cap is nowhere near truncation risk. Pricing:
  **$0.40 / 1M input tokens, $1.60 / 1M output tokens** (cached input
  $0.10/1M) — source: https://developers.openai.com/api/docs/pricing,
  checked 2026-09-13.
- Embeddings: `text-embedding-3-small`, 1536 dimensions. Pricing:
  **$0.02 / 1M tokens** — same source.

**What was actually checked, against the real running app with real API
calls** (BMW 3 Series (E90) 320d unless noted):

1. **Supported question → grounded answer.** "The driver's side window
   will not go up anymore, I do not hear the motor at all" → `guidance`,
   cited chunks 6/7/11 (window-mechanism + "no motor sound" + basic-circuit
   sections). Read the excerpts: they genuinely support the stated
   hypothesis, not just valid-looking ids. **Pass.**
2. **Follow-up retains vehicle + conversation context.** "Only the driver
   side door, the other doors and the radio still work fine" (no vehicle
   restated) → correctly narrowed the same hypothesis to "local to the
   driver's door," reusing the prior turn's symptom without being told
   again. **Pass.**
3. **Incompatible vehicle excluded (unrelated make).** Same window question
   asked on a Ford Focus (zero knowledge-corpus coverage) →
   `insufficient_evidence`, `providerStatus: empty_retrieval`, **zero**
   generation calls made (the deterministic empty-retrieval short-circuit
   fired, not a wasted paid call). **Pass.**
4. **Incompatible vehicle excluded (same model, different generation) —
   the specific fix from the previous session.** Asked a BMW 3 Series
   Sedan **(G20) 330i** (variant 23006, a totally different generation and
   engine from the E90 320d) directly "...could this be a timing chain or
   N47 issue?" — the E90-N47-specific document (`documents.id = 5`) did
   **not** appear among the retrieved evidence, confirmed independently by
   querying `document_vehicle_links` for that document/variant pair
   (zero rows). The 6 genuine generic documents did surface, correctly,
   since they make no generation-specific claim. **Pass — this is real
   proof the generation-scoping fix works with a live model and real
   embeddings, not just the unit/integration tests.**
5. **Unsupported question does not fabricate a spec.** Asked the E90 320d
   for "the exact automatic transmission fluid type and torque converter
   rebuild torque spec" → `insufficient_evidence`, `sourceChunkIds: []`
   (no false citations), explicitly listed the two facts as missing rather
   than inventing plausible-sounding numbers. **Pass.**
6. **Evidence & Debug shows real metadata, not placeholders.** Verified via
   both the API directly and the browser drawer: real request id, real
   `promptTokens`/`completionTokens`, real retrieval/generation
   millisecond timings, real per-chunk vector/text/fused scores, and
   `Cost estimate unavailable` shown honestly (no fabricated dollar figure)
   since the UI has no wired-in verified pricing table. **Pass.**
7. **Persistence + isolation with real data.** Reloaded a session with 6
   real messages (3 turns) — all recovered from the database, not
   regenerated. A request with no visitor cookie got `404 Conversation not
   found`. **Pass.**
8. **Frontend rendering**, checked in an actual browser (Claude-in-Chrome):
   sending a message showed the structured answer card (answer-type badge,
   hypotheses with clickable `[chunk_id]` citation buttons, safe checks,
   an amber cautions box for the brake-related question, follow-up
   question buttons, an evidence-count link), the Evidence & Debug drawer
   opened with the real data described above, and the page correctly
   recovered the same answer after a full reload. **No browser console
   errors, no server errors in either log during any of this.** **Pass.**
   (One earlier click on the Send button didn't register — a repeat of
   last session's known automation-tool flakiness with this specific UI,
   not an app defect; retried once and it worked normally. Playwright,
   a different driver, has never shown this issue.)

**Not claimed**: this was a small, bounded verification set (5 real
generation calls total), not a statistical evaluation of retrieval
ranking quality or answer quality at scale. Mocked unit tests
(`ChatOrchestrationServiceTest`) prove the surrounding *policy* — this
session is what actually proves live generation and retrieval work.

## Cost accounting

**Estimated, before any calls were made** (word-count based, using the
exact chunk word counts from the corpus and the official per-token
prices above): embedding the 37-chunk corpus ≈ 4,534 estimated tokens ≈
**$0.0001**; a 6–8 turn verification set at an assumed ~1,300 input /
~400 output tokens per turn ≈ **$0.009**. Total estimate: **≈ $0.01**,
comfortably under the $0.25 cap, so no scope reduction was needed.

**Measured, from what actually happened** (queried from `rag_runs`,
which stores the `usage` OpenAI's own response reported per call — this
is real API-reported usage, not a local guess):

| | Calls | Prompt tokens | Completion tokens |
|---|---|---|---|
| Generation (`gpt-4.1-mini`) | 5 successful (+1 short-circuited to `empty_retrieval` with **zero** generation calls, +8 earlier `missing_key` turns from before the key was configured, also zero calls) | 8,617 | 1,392 |

Generation cost = 8,617/1,000,000 × $0.40 + 1,392/1,000,000 × $1.60
= $0.003447 + $0.002227 = **$0.005674, measured**.

Embedding corpus cost: **not separately measured** this run — the SDK
usage field for that specific call wasn't being captured yet (fixed this
session, see below), and re-running it just to backfill a number would
have meant a second paid call for a already-idempotent, already-cached
result; not worth it for a ~$0.0001 figure. Reported here as the
word-count **estimate**: **≈ $0.0001**. Query embeddings for the 6 chat
turns above (~20–40 tokens each): **≈ $0.000004, estimated** (not worth
separately measuring at this size).

**Total for this session: ≈ $0.0058, all measured except the ~$0.0001
embedding-corpus estimate.** Comfortably under the $0.25 cap (≈2.3% used).

**Illustrative cost per 100 ordinary chat turns**, extrapolated from the 5
measured turns above (average 1,723 prompt / 278 completion tokens per
turn): (1,723×0.40 + 278×1.60)/1,000,000 × 100 ≈ **$0.11 per 100 turns**.
This is an *estimate extrapolated from a 5-turn sample*, not a guarantee —
real turns vary with conversation length, evidence-chunk count, and
question complexity, and this number will move as the sample grows.

**Cost controls now in place** (see `ChatOrchestrationService` and
`pipelines/embeddings/openai_embeddings.py`):

- Embedding ingestion is idempotent by content hash + embedding model +
  chunking version — confirmed by re-running `ingest-knowledge` right
  after the real run: 8/8 documents `unchanged`, 0 new API calls.
- Chat retrieval is capped by both chunk count (`CHAT_MAX_RETRIEVED_CHUNKS`,
  default 6) **and**, new this session, a total evidence **word budget**
  (`CHAT_MAX_EVIDENCE_WORDS`, default 1200) — chunk count alone doesn't
  bound spend if individual chunks were large. Also de-duplicates any two
  retrieved chunks with byte-identical content before building the prompt.
  Covered by two new unit tests
  (`evidenceWordBudgetStopsAddingFurtherChunksOnceExceeded`,
  `duplicateContentAcrossChunkIdsIsSentOnlyOnce`).
- Retries: at most 2 (3 total attempts), only on `RateLimitError`/
  `APITimeoutError` — never on auth/invalid-request/unsupported-model
  errors. Fixed an off-by-one this session (Python's embedding retry was
  `stop_after_attempt(4)` = 3 retries; now `stop_after_attempt(3)` = 2, to
  actually match this rule).
- Empty retrieval short-circuits to a deterministic `insufficient_evidence`
  answer with **zero** generation calls — verified live above (check #3).
- Missing key short-circuits with **zero** OpenAI calls of any kind.
- One generation call + one embedding call per ordinary turn, always — no
  second model call for grading, formatting, or citation-checking
  (citation validity is checked deterministically server-side against the
  actually-retrieved chunk id set).
- No paid web search, no hosted file-search storage, no automatic online
  research per message.

## Corpus provenance & applicability (unchanged from last session, still accurate)

Reviewed all 8 knowledge documents again before ingesting real embeddings
— no changes were needed; the review below still holds:

- 6 documents (`provenance: synthetic_demo`, `review_status: draft`):
  genuinely generic guidance, no invented BMW-specific numbers.
- 1 document (`04-bmw-e90-320d-n47-known-issues.md`, `provenance:
  public_source`, `documents.id = 5`): real forum URLs + retrieval date,
  explicitly marked unverified community reporting, not an official BMW
  bulletin. **Scoped to exactly the 13 real `(E90) 320d` variants** via
  `applies_to_variant_name_contains` (last session's fix) — confirmed live
  this session (check #4 above) that this actually prevents cross-
  generation leakage with a real model call, not just in tests.
- 1 fixture (`toyota-corolla-hybrid-battery-warning.md`): `[TEST FIXTURE]`
  in its own title, scoped only to Toyota Corolla.
- No document states a specific horsepower figure for the demo vehicle,
  because the vehicle's own catalogue `power` field disagrees with its own
  `version` label (116 HP vs. the labeled 177 HP) — flagged as a
  `data_quality_issue`, never silently treated as verified. See
  `data-findings.md` §5b/§7.

## Automated tests (re-run this session where the change touched them)

- Python: **37/37 pass** (`cd pipelines && pytest tests/`) — untouched this
  session (no Python files changed).
- Java: **31/31 pass** (`cd apps/api && ./mvnw test`) — was 24 going into
  this session (22 → 24 was the prior session's own delta); +7 this
  session: `CatalogueRepositoryIT` (4 tests: the size-20 pagination bug
  reproduced and fixed, case-insensitive ordering, demo-variant
  coverage-gated resolution) and `CatalogueControllerTest` (3 tests: page
  size clamp, clamp covers the real catalogue maximum, demo-vehicle 404
  when unresolvable).
- Playwright: **10/10 pass** (`cd apps/web && npx playwright test`),
  re-run twice for stability with zero flakiness. Pre-existing
  `golden-path.spec.ts` needed two unrelated small fixes for this
  session's own frontend changes (the `Combobox` search placeholder text
  changed, and dropdown options now carry `role="option"` instead of the
  implicit `role="button"` for correct ARIA semantics) plus the network
  stub described above. New `layout-and-catalogue.spec.ts` (7 tests) is
  the "focused regression tests for the actual pagination and
  demo-selection bugs" this session's instructions asked for: two scroll-
  independence tests (seeded via direct SQL, zero API cost), three
  catalogue-browsing tests (reach a late-alphabet manufacturer and a
  deep-in-the-list model purely by scrolling, no search text; keyboard
  selection), and two demo-vehicle-identity tests (panel names the E90
  320d and the example button opens that exact variant; landing page and
  in-app panel agree).

## Known limitations

- Only the 13 real BMW E90 320d variants have scenario-specific knowledge
  coverage. Every other vehicle in the ~27k-variant catalogue gets an
  honest `insufficient_evidence` response, by design — confirmed live with
  a Ford Focus this session.
- The BMW E90 320d demo vehicle's own `power` field is internally
  inconsistent with its own version label — a genuine source-data defect,
  flagged, not silently fixed.
- One BMW-specific knowledge document is sourced from public owner forums,
  not an official BMW bulletin, and is explicitly labeled unverified.
- The embedding-ingestion cost for this session's corpus embedding is an
  estimate, not a measured figure (the usage-tracking fix landed after
  that call was already made; it will report real numbers for any future
  ingestion run that actually calls OpenAI).
- Live retrieval ranking quality was checked qualitatively (5 real
  questions, read the excerpts, confirmed they support the claims) — not
  measured against a larger labeled evaluation set.
- No CI pipeline is configured; all verification above was run locally.
- This session's Claude-in-Chrome browser-automation tool had one
  recurrence of prior flakiness driving the composer/send-button
  (a click not registering); retried successfully. Not an app defect —
  Playwright drives the identical flow reliably every time.
- This session's remaining limitations: the `Combobox` error/retry state
  (a failed fetch showing "Couldn't load options." + Retry) is implemented
  and code-reviewed but not separately live-browser-verified against an
  actual induced network failure. The `variantsRequestGuard` stale-request
  protection in `VehiclePicker` is implemented and covered by the same
  pattern already proven elsewhere in this codebase (`Combobox`'s own
  `requestId` guard) but wasn't separately stress-tested by rapidly
  clicking through several manufacturers in a live browser. The mobile
  drawer's independent list-scrolling and "closing restores normal
  interaction" beyond hamburger-visible/no-overflow/drawer-opens (already
  covered) weren't separately re-verified this session — no drawer-related
  code changed, so this is unchanged risk from before, not new.

## Not attempted (explicitly out of scope for this pass)

- Additional demo vehicles/scenarios beyond the BMW E90 320d family.
- Streaming chat responses.
- Document imagery/diagrams.
- Public deployment configuration.
- A statistically meaningful retrieval-quality evaluation (would need a
  larger labeled question set and non-trivial additional API spend for
  limited portfolio-demo value).
- Paid model-based grading of answer quality — the master instructions for
  this task explicitly said not to add this.
