# CLAUDE.md

Engineering working notes for continuing work on this repository. A
portfolio README will be written later, once the owner explicitly asks
for it — see the non-negotiable below.

## What this project is

Repair V2: a rebuilt, AI-powered automotive diagnosis demo. Cars only. A
Python pipeline reconciles a private scraped CSV export and a legacy
PostgreSQL dump into one canonical catalogue; a Java/Spring API serves the
catalogue and runs a RAG-based chat diagnosis over a small hand-written
knowledge corpus; a Next.js app is the UI. See `docs/planning/` for the
architecture, data-pipeline, and setup writeups.

## Non-negotiables (carried over from the original task spec)

- Everything authored (code, comments, UI copy, docs) is in English.
- **Do not create or modify any project README.md until the owner
  explicitly requests it.** (A README was mistakenly created once during
  this project's history and was removed — this rule stands regardless of
  what seems otherwise implied by "finish the portfolio" style requests.)
  Engineering notes and local setup instructions belong in
  `docs/planning/` instead.
- Never touch the owner's original local `repair_db` PostgreSQL database.
  This project's own Postgres runs in Docker on port 5544, never 5432.
- Never commit `data/raw/` (the private dataset), `.env`, credentials, or
  local database volumes.
- Never fabricate test results, live-verification claims, dataset
  provenance, or mechanical facts (torques, fuse numbers, service
  intervals, OEM specs) not backed by a real source. See
  `docs/planning/data-findings.md` for what's actually been verified about
  the source data, and label synthetic knowledge-corpus content as such.
- Citations in chat answers must only ever reference chunk ids that were
  actually retrieved for that turn — validated server-side in
  `ChatOrchestrationService.validateCitations`, not just trusted from the
  model's output.

## Where things live

- `data/raw/` — private inputs, git-ignored. `data/fixtures/` — small
  public subset used by automated tests when the private data isn't
  present.
- `data/knowledge/` — the hand-written Markdown knowledge corpus.
- `pipelines/` — Python ingestion + embeddings CLI (`ingest.cli`).
- `apps/api/` — Java 21 / Spring Boot 4.1 API. JDBC (no JPA), Flyway
  migrations in `src/main/resources/db/migration`.
- `apps/web/` — Next.js 16 / React 19 / Tailwind 4 frontend.
- `docs/planning/` — data findings, local dev instructions, status log.
  These are working notes, not marketing copy.

## Current status

See `docs/planning/status.md` for exactly what's verified vs. not, and why.
As of the last update: full vertical slice implemented and tested (Python:
37 pytest tests; Java: 24 JUnit/Testcontainers tests; web: Playwright golden
path). A real `OPENAI_API_KEY` is now configured and **live RAG has been
verified end-to-end**: real embeddings for the 8-document/37-chunk
knowledge corpus, real `gpt-4.1-mini` generation calls, grounded answers
with citations that genuinely support their claims, follow-up context
retention, and — importantly — live confirmation that a different BMW
generation (G20 330i) does not retrieve the E90-N47-specific document.
Total measured spend for the verification session: ≈$0.006.

## Local development

See `docs/planning/local-development.md` for exact commands (start the DB,
import the catalogue, ingest the knowledge corpus, run each app, run each
test suite).
