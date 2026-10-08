# CLAUDE.md

Engineering notes for anyone (human or agent) changing this repository.

## What this project is

Repair is a motorcycle maintenance companion: pick a bike from the catalogue, keep it in
a personal garage, ask maintenance and troubleshooting questions answered with retrieval
scoped to that exact manufacturer, model and year, and record completed maintenance from
chat or forms into a per-bike service history with deterministic service status.

The active product is the motorcycle flow. Coverage today is Yamaha only (model-year
Markdown files under `knowledge/motorcycles/`). The car prototype that preceded it still
lives in the code (`chat`, `conversation`, `catalogue`, `dataquality` packages, the
`/quality` page and tables from V1–V5); it is legacy and must not be extended.

The current catalogue schema is not final: a canonical motorcycle model with per-value
provenance is planned. Until it lands, keep changes compatible with the existing tables.

## Stack and layout

- `apps/api/` — Java 21, Spring Boot 4.1, plain JDBC via `JdbcClient` (no JPA), Flyway
  migrations in `src/main/resources/db/migration`, Spring Security with Google OAuth2.
- `apps/web/` — Next.js 16 App Router, React 19, TypeScript, Tailwind 4.
- `pipelines/` — Python ingestion and embedding CLI (`python -m ingest.cli`).
- `knowledge/motorcycles/<manufacturer>/<model>/<year>.md` — curated knowledge base.
- `docs/` — architecture and design notes; `docs/planning/` holds working notes.
- PostgreSQL 17 + pgvector runs from `docker-compose.yml` on host port 5544.

## Commands

- Database: `docker compose up -d db`
- API tests (unit + Testcontainers, needs Docker): `cd apps/api && ./mvnw test`
- Pipeline tests: `cd pipelines && pip install -e ".[dev]" && pytest`
  (integration tests skip when Postgres on 5544 is unreachable)
- Web checks: `cd apps/web && npm ci && npm run lint && npx tsc --noEmit && npm run build`
- E2E (API and DB running): `cd apps/web && npm run test:e2e`

CI (`.github/workflows/ci.yml`) runs the API, pipeline and web checks on every pull request.

## Non-negotiables

- Everything authored in the repository (code, comments, UI copy, docs, commit messages) is in English.
- Never fabricate mechanical facts (torques, capacities, intervals, specifications), test
  results, live-verification claims or data provenance. A value without a reliable source
  stays unknown. Never copy a value from one model year or variant to another without a
  source that explicitly covers both.
- Knowledge content is our own structured wording of verifiable facts; do not reproduce
  copyrighted text from manuals or websites.
- Structured facts are extracted deterministically (regex/structure), never by a model.
- Chat citations may only reference chunks actually retrieved for that turn; this is
  validated server-side (`MotoChatOrchestrationService`, `MotoDiagnosticAnswer`).
- Maintenance writes from chat must stay behind `ActionIntentGuard`: hypothetical,
  planned or uncertain statements never become service records.
- Every garage, chat and maintenance query is scoped by the authenticated `user_id`.
- Never commit `.env`, credentials, `data/raw/` or database volumes. Never point anything
  at the owner's local `repair_db` database; this project uses its own database on port 5544.
- Tests must never call OpenAI; stub the network boundary.
- Migrations are additive. Dropping or rewriting tables or columns requires the owner's
  explicit approval in the pull request.

## Documentation

Keep `README.md` accurate: update it when a change alters features, setup, configuration,
architecture, the database or commands. Describe only what is implemented and verified;
plans go under a clearly marked roadmap. No promotional text and no tool attributions.

## Contributions and automation

- Work happens on branches and pull requests; `main` is protected. No force pushes and no
  history rewriting. Commit dates are never altered.
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`,
  `docs:`, `perf:`, `chore:`, `ci:`, `build:`), imperative mood, one coherent change per commit.
- Automated changes are produced by the autodev system: the agent runs in an isolated
  GitHub Actions job and publishes through the autodev GitHub App, so its commits and pull
  requests are attributed to that bot and labelled `autodev`.
- Automated sessions must not modify `.github/`, this file, migrations that drop data, or
  security configuration without an explicit, approved task.
