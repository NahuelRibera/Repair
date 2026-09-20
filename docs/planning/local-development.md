# Local development

Primary target: macOS (Apple Silicon or Intel) with Docker Desktop. Commands
are POSIX shell; on Windows use WSL2 or Git Bash.

All commands below assume your shell's working directory is the repo root
unless a `cd` is shown.

## Repair V2 (motorcycle product) — quick start

The steps below (1, 2, 5, 6) apply unchanged. Step 3 (car catalogue
import) and the car-specific parts of step 4 are **not needed** for the
motorcycle product — skip straight to motorcycle knowledge ingestion:

```bash
docker compose up -d db                         # 1. start Postgres
cd apps/api && ./mvnw spring-boot:run            # 2. applies V1-V7 migrations, then Ctrl+C
cd pipelines
python3 -m venv .venv && source .venv/bin/activate && pip install -e ".[dev]"
python -m ingest.cli ingest-motorcycle-knowledge # 4. ingest knowledge/motorcycles/**/*.md (needs OPENAI_API_KEY)
cd apps/api && ./mvnw spring-boot:run             # 5. API on :8082
cd apps/web && npm install && npm run dev         # 6. web on :3000
```

Then open <http://localhost:3000> — "Choose your bike" walks through the
dynamic manufacturer → model → year catalog built from whatever's been
ingested (Yamaha MT-07/MT-09/MT-09 SP/Ténéré 700/Ténéré 700 World Raid
out of the box). See `docs/knowledge-ingestion.md` for how to add a new
manufacturer/model/year with zero code changes.

The car prototype (catalogue import, car knowledge ingestion, `/quality`
page) still works exactly as documented below — it's a preserved,
independent code path, not needed for the motorcycle product.

---

## 0. Prerequisites

- Docker Desktop running
- Java 21 (the project uses the Maven Wrapper, `./mvnw` — no local Maven install needed)
- Node.js 20+ and npm
- Python 3.11+

## 1. Start the database

```bash
docker compose up -d db
```

This starts PostgreSQL 17 with pgvector on **host port 5544** (deliberately
non-default so it never collides with an existing local Postgres instance,
including the owner's original `repair_db`). Data persists in the named
volume `repair_v2_pgdata` across restarts — `docker compose down` does not
touch it; only `docker compose down -v` would.

Check it's healthy:

```bash
docker compose ps
```

## 2. Run the database migrations

Flyway runs automatically when the API starts (step 5). To apply migrations
without starting the full API, you can also just start the API once and
stop it — there is no separate `flyway migrate` CLI wired up in this
project.

## 3. Import the vehicle catalogue

The owner's private dataset lives in `data/raw/` (git-ignored, never
committed). If you don't have it, a small distributable fixture lives in
`data/fixtures/` and is what the automated tests use — see
`pipelines/tests/test_importer_integration.py`.

```bash
cd pipelines
python3 -m venv .venv        # first time only
source .venv/bin/activate
pip install -e ".[dev]"      # first time only

python -m ingest.cli import --namespace all
```

Expect (with the real private dataset): ~29k raw records ingested, ~27k
canonical variants, a few thousand flagged data-quality issues. Re-running
is idempotent — see `docs/planning/data-findings.md` for what the importer
actually does and why.

To run against the small public fixtures instead of the private dataset:

```bash
VEHICLES_DIR=../data/fixtures/vehicles \
LEGACY_SQL_PATH=../data/fixtures/legacy_dump_fixture.sql \
python -m ingest.cli import --namespace all
```

## 4. Ingest the knowledge corpus (requires OPENAI_API_KEY)

Set your key once in the repo-root `.env` file (copy `.env.example` to
`.env` if you haven't already — see "Environment configuration" below),
then:

```bash
cd pipelines
source .venv/bin/activate
python -m ingest.cli ingest-knowledge
```

Both the Python CLI and the Java API read `OPENAI_API_KEY` from that same
`.env` file automatically — there's no need to `export` it manually unless
you want to override it for one command.

This embeds the Markdown documents under `data/knowledge/` and links them
to the BMW 3 Series (E90) 320d / Toyota Corolla fixture catalogue rows.
Idempotent by content hash + embedding model + chunking version — re-running
after an unrelated catalogue re-import does not re-embed unchanged
documents. Confirmed live: the full 8-document/37-chunk corpus costs about
$0.0001 to embed at `text-embedding-3-small`'s $0.02/1M-token rate;
re-running immediately afterward reports 8/8 `unchanged`, 0 new API calls.
The CLI prints the real token usage OpenAI reports for the run (not a
local guess) whenever it actually calls the API.

Without a key, you can still verify the parsing/chunking logic:

```bash
python -m ingest.cli ingest-knowledge --dry-run
```

### Cost controls (why this stays cheap)

- Both the generation model (`gpt-4.1-mini`) and the embedding model
  (`text-embedding-3-small`) are the small/economical tier, not a flagship
  model — configurable via `OPENAI_GENERATION_MODEL`/`OPENAI_EMBEDDING_MODEL`
  in `.env`, but there's no reason to change them for this demo's scale.
- A chat turn with no applicable evidence (empty retrieval) or a missing
  key short-circuits to an honest answer with **zero** OpenAI calls — it
  never falls through to a wasted generation request.
- Retrieved evidence is capped by both chunk count
  (`CHAT_MAX_RETRIEVED_CHUNKS`, default 6) and a total word budget
  (`CHAT_MAX_EVIDENCE_WORDS`, default 1200), and duplicate-content chunks
  are collapsed before being sent — so a single ordinary chat turn costs at
  most one embedding call + one bounded generation call, never more.
- Retries are capped at 2 (3 total attempts) and only for transient
  failures (rate limits, timeouts) — auth/invalid-request/unsupported-model
  errors are never retried or silently escalated to a different model.
- Real measured cost from a 5-turn live verification session: **≈$0.006
  total**; see `docs/planning/status.md` for the full accounting and an
  illustrative (not guaranteed) per-100-turn extrapolation.

## 5. Run the API

```bash
cd apps/api
./mvnw spring-boot:run
```

Runs on **port 8082** (8080 is deliberately avoided — an unrelated
RabbitMQ management UI is commonly already bound there locally). Health
check: `curl http://localhost:8082/actuator/health`.

Without `OPENAI_API_KEY` set, the API still starts normally; catalogue and
data-quality endpoints work, and chat endpoints return an honest "AI not
configured" response instead of erroring.

To run it in the background instead of a dedicated terminal tab (so it
survives your shell session ending):

```bash
mkdir -p ../../.logs   # repo-root .logs/, already git-ignored
cd apps/api
nohup ./mvnw -q spring-boot:run > ../../.logs/api.log 2>&1 &
disown
```

## 6. Run the web app

```bash
cd apps/web
npm install     # first time only
npm run dev
```

Open **http://localhost:3000**. The web app proxies `/api/*` to the Java
API (see `apps/web/next.config.ts`, `API_ORIGIN` env var) so the browser
only ever talks to one origin. Same background pattern:

```bash
cd apps/web
nohup npm run dev > ../../.logs/web.log 2>&1 &
disown
```

## Clearing demo/QA data (optional, manual only)

`scripts/clear-demo-data.sh` clears Garage vehicles, maintenance events,
preferences, and conversation history (both the authenticated motorcycle
domain and the legacy anonymous car-prototype path) from your **local**
database — never the motorcycle catalog or knowledge base. It is never run
automatically by anything (no hook, no CI job, no application code path)
and is deliberately hard to trigger by accident:

```bash
scripts/clear-demo-data.sh                                        # dry run: prints target + row counts, deletes nothing
scripts/clear-demo-data.sh --yes                                   # still a dry run — --yes alone is not enough
scripts/clear-demo-data.sh --yes --confirm CLEAR-REPAIR-DEMO-DATA   # actually deletes
```

Before doing anything destructive, it also refuses to run at all unless
the target is unambiguously *this* project's local dev database — see
`scripts/lib/db-safety-guard.sh`:

- host must be `localhost` or `127.0.0.1` (never a remote or shared host);
- the database name must be exactly `repair_v2`;
- it hard-refuses the owner's separate car-prototype database
  (`repair_db`) and any name that merely contains `prod`.

`scripts/clear-demo-data.guard.test.sh` unit-tests that guard logic (pure
bash, no database needed):

```bash
bash scripts/clear-demo-data.guard.test.sh
```

## 7. Stopping everything

If you started the API/web app in a terminal tab: `Ctrl+C` there.

If you started them in the background (as above), find and stop just
those processes rather than anything else running on your machine:

```bash
pkill -f "spring-boot:run"   # stops the Java API
pkill -f "next dev"          # stops the Next.js dev server
```

```bash
docker compose down          # stops the database container, KEEPS the volume
```

Never run `docker compose down -v` as part of routine shutdown — that
deletes the persisted catalogue/knowledge/conversation data. Never use
`pkill` with a broader pattern than the two above — this machine may be
running other, unrelated Java/Node processes (e.g. an unrelated RabbitMQ
container is normal to see in `docker ps`; leave it alone).

## Environment configuration

Copy `.env.example` to `.env` at the repo root and fill in `OPENAI_API_KEY`
(get one at <https://platform.openai.com/api-keys>). `.env` is git-ignored
— never commit it, print it, or paste its contents anywhere, including
into a chat/agent conversation.

Both services load it automatically, by design, from wherever you run them:

- **Python** (`pipelines/ingest/config.py`) uses `python-dotenv`'s
  `load_dotenv()`, which searches upward from the calling file's directory.
- **Java** (`apps/api/src/main/java/dev/repair/api/config/DotenvLoader.java`,
  invoked from `ApiApplication.main()` before Spring starts) does the
  equivalent: searches upward from the process's working directory for a
  file named `.env`, parses it, and sets values as JVM system properties —
  but only for keys not already set as a real environment variable, so a
  real deployment env var always wins over this file.

Neither reads `.env` values into logs, browser code, or `NEXT_PUBLIC_*`
variables. Without a key, the catalogue and data-quality pages work
normally and chat requests return a clear "OpenAI is not configured"
response instead of failing.

## Running the tests

```bash
# Python: unit tests + Testcontainers-style isolated-schema integration
# tests (needs the db container from step 1 running; skips gracefully if not)
cd pipelines && source .venv/bin/activate && python -m pytest tests/ -v

# Java: unit tests (mocked OpenAI, no key needed) + Testcontainers
# integration tests (spins up its own throwaway Postgres+pgvector container,
# independent of the docker-compose `db` service)
cd apps/api && ./mvnw test

# Web: type check, lint, production build
cd apps/web && npx tsc --noEmit && npm run lint && npm run build

# Web: end-to-end (requires the API + db running; starts its own Next.js
# dev server automatically). golden-path.spec.ts additionally requires
# the API running with SPRING_PROFILES_ACTIVE=dev (see below and
# docs/authentication.md) — every other spec file runs against the
# API's normal profile. The chat-turn network call is always stubbed,
# so no real OPENAI_API_KEY spend happens regardless of what's in .env.
cd apps/api && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run   # separate terminal
cd apps/web && npx playwright test
```

None of the automated tests call the real OpenAI API. The only thing that
requires a real `OPENAI_API_KEY` is step 4 (embedding the knowledge corpus)
and manually exercising a live chat conversation in the browser.

`tests/e2e/golden-path.spec.ts` and the ownership-isolation test within it
sign in via the real backend using `tests/e2e/dev-auth.ts` (the manual-QA-
only `DevLoginController`), so they only run correctly against an API
started with the `dev` profile as shown above — see docs/authentication.md
"Testing without a Google account". Every other E2E spec mocks
`/api/me` instead and runs against the API's normal profile.

The motorcycle-specific test files (all offline, no API key needed):
`pipelines/tests/test_motorcycle_facts.py`,
`pipelines/tests/test_ingest_motorcycle_knowledge_integration.py`,
`apps/api/src/test/java/dev/repair/api/motochat/*`,
`apps/api/src/test/java/dev/repair/api/garage/*`.

## Ports reference

| Service | Port | Notes |
|---|---|---|
| Next.js web app | 3000 | `npm run dev` |
| Java API | 8082 | avoids 8080 (often already bound) |
| Postgres + pgvector (app) | 5544 | avoids 5432 (the owner's existing Postgres) |
| Postgres (legacy-dump inspection, optional) | 5545 | `docker compose --profile legacy-inspection up -d legacy-db` |

## Log locations (when run in the background per steps 5/6)

- API: `.logs/api.log` (repo root, git-ignored)
- Web: `.logs/web.log` (repo root, git-ignored)
