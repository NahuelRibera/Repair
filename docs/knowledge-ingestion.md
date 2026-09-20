# Motorcycle knowledge ingestion

How `knowledge/motorcycles/**/*.md` becomes the dynamic catalog and the
RAG-retrievable chunk/fact store. See `docs/repair-v2-architecture.md`
sections 1–4 for the full design; this is the operational how-to.

## Running it

```bash
cd pipelines
source .venv/bin/activate       # or: uv venv / pip install -e .[dev]
python -m ingest.cli ingest-motorcycle-knowledge            # real run (needs OPENAI_API_KEY)
python -m ingest.cli ingest-motorcycle-knowledge --dry-run  # parse/chunk only, no OpenAI call, no writes
python -m ingest.cli ingest-motorcycle-knowledge --prune    # also delete DB rows for files removed from disk
```

Idempotent: re-running with no file changes reports every document as
`unchanged`, writes nothing, and makes zero OpenAI calls. Only changed or
new files are (re)embedded.

## Adding a new motorcycle

1. Create `knowledge/motorcycles/<manufacturer-slug>/<model-slug>/<year>.md`
   (or `<year-range>.md` — the filename doesn't matter, only the
   frontmatter does).
2. Add frontmatter:
   ```yaml
   ---
   manufacturer: Honda
   model: CB650R
   year_from: 2025
   year_to: 2025
   market: EU
   aliases:
     - CB 650 R
   last_verified: 2026-09-14
   ---
   ```
   `manufacturer`/`model`/`year_from`/`year_to` are required; everything
   else is optional. `model` is the **canonical** name shown in the
   catalog — put accented/alternate spellings in `aliases`, not in
   `model` itself, if you want the canonical name to stay ASCII-friendly
   (see the existing "Tenere 700" / alias "Ténéré 700" pattern).
3. Write the body using the same section shape as the existing Yamaha
   files (`## Quick specifications`, `## Periodic maintenance`, `##
   Basic troubleshooting`, etc.) — sections are optional per file, add
   what's real and verified, skip what isn't.
4. Run `python -m ingest.cli ingest-motorcycle-knowledge`.
5. The manufacturer/model/year now appears through
   `GET /api/motorcycles/manufacturers` → `.../models` → `.../years`
   with **zero application code changes**. This is proven by
   `test_extensibility_new_manufacturer_requires_no_code_change` in
   `pipelines/tests/test_ingest_motorcycle_knowledge_integration.py`.

## Chunking

Reuses `embeddings/chunker.chunk_markdown_body` unchanged — heading-aware
(`#`/`##`/`###` boundaries), with a 220-word budget and 30-word overlap
fallback for any section that runs long. A chunk's `category` is derived
from its top-level (`##`) heading: `Periodic maintenance` →
`maintenance`, `Basic troubleshooting` → `troubleshooting`, `Quick
specifications` → `specification`, `Electronic rider systems` /
`Model-specific systems` → `model_specific`, everything else → `other`.

## Deterministic facts

`embeddings/motorcycle_facts.py` extracts a small set of numeric/text
facts by regex from known section shapes (see the module for the exact
patterns and `docs/repair-v2-architecture.md` section 4 for the policy).
A fact type that doesn't match a given file's exact wording simply isn't
extracted for that file — the value is still reachable through normal
chunk retrieval. Extending coverage means adding an alternative pattern
to an existing fact type, or a new fact type entirely; both are pure
Python changes, no schema migration needed unless you introduce a
genuinely new *kind* of fact.

## What's NOT handled automatically

- **Removed files**: only pruned with `--prune` (off by default, to
  avoid an accidental partial `find` wiping data).
- **Cross-manufacturer alias collisions**: two different manufacturers
  using the same alias text is not currently detected or prevented —
  aliases are scoped to `model_id`, so this can't merge two models, but
  it also isn't flagged.
- **Market-specific fact conflicts**: if two documents for the same
  model/year (different `market`) disagree on a fact, the *first row
  returned* wins in `MotorcycleCatalogRepository.findFacts` — there is no
  market-selection step yet.
