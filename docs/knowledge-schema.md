# Knowledge file schema

Schema version: **1.1** (1.1 adds market-suffixed file names and the `market-sibling` rule). The machine-readable definition is
[`pipelines/knowledge/schema.py`](../pipelines/knowledge/schema.py) (`load_schema()` returns it as
plain data). The schema describes what the current corpus under `knowledge/motorcycles/` already
uses; it does not require rewriting any file.

Validate a file with `knowledge.schema.validate_file(path)`; it returns a list of problems (missing
front matter or sections, invalid identifiers, a year range that disagrees with the file name,
duplicate headings, out-of-order required sections). `pipelines/tests/test_knowledge_schema.py`
runs it over the whole corpus.

## Running the validator

```bash
cd pipelines
python -m knowledge.validate                    # whole corpus, text output
python -m knowledge.validate --format json      # machine-readable
python -m knowledge.validate path/to/file.md    # files or directories
python -m knowledge.validate --baseline         # ignore errors recorded in the baseline
python -m knowledge.validate --write-baseline   # rewrite knowledge/baseline.json
```

Each finding carries the file, the line (where one is known), a rule id and a severity. The exit
code is 1 when at least one error remains (after applying `--baseline`, if given) and 0 otherwise;
warnings never fail the run. The checks are deterministic (regex and structure) and never modify
a knowledge file.

| Rule | Severity | Reports |
|---|---|---|
| `parse-error` | error | unreadable file or invalid YAML front matter |
| `fm-missing-key` / `fm-type` | error | missing or empty required key; wrong type for `aliases`, `last_verified`, `provenance` |
| `fm-unknown-key` | warning | key not in the schema |
| `id-invalid` | error | invalid manufacturer, model, market, variant or year value |
| `year-range` | error | `year_from` greater than `year_to` |
| `path-mismatch` | error | directory or file name disagrees with manufacturer, model or `year_from` |
| `section-missing` / `section-duplicate` / `section-order` | error | required sections absent, repeated or out of order |
| `unit-invalid` | error | non-canonical spelling of a schema unit after a number (`Nm`, `kpa`, `litres`, `ml`, ...); for `ml`/`mL` the message also says to divide the value by 1000 (`400 mL` = `0.4 L`) |
| `unit-unknown` | warning | fact-index value with a unit the schema and the accepted extras (`bar`, `kg`, `V`, `Ah`, `rpm`, `years`, ...) do not list |
| `entry-duplicate` / `entry-overlap` | error | two files for the same manufacturer, model, market and variant with identical or overlapping years |
| `market-sibling` | error | a served file covers a model year that another market already covers (retrieval does not select by market yet; such files go to `knowledge/staging/`) |
| `fact-index-duplicate` | error | the fact index lists one fact twice with different values |
| `fact-index-conflict` | error | a `- Label: value` line or two-column table row elsewhere in the file has the same label as a fact-index row but a different quantity in the same unit |

`pipelines/knowledge/baseline.json` records the errors tolerated today (currently none), keyed by
file, rule and message so unrelated edits do not shift it.
`pipelines/tests/test_knowledge_validate.py` fails when the corpus produces an error that is not in
the baseline. Fix errors rather than adding them to the baseline; regenerate it only to remove
entries, or when a new rule is introduced and the existing corpus cannot yet satisfy it.

## Layout

`knowledge/motorcycles/<manufacturer-slug>/<model-slug>/<year_from>.md`, or `<year_from>-<market>.md` (market in
lower case, e.g. `2021-eu.md`) for a second market of the same model year. Slugs are produced by
`ingest.normalize.slugify` from the `manufacturer` and `model` values.

## Front matter

| Key | Type | Required | Notes |
|---|---|---|---|
| `manufacturer` | string | yes | Display name |
| `model` | string | yes | Display name |
| `year_from`, `year_to` | integer | yes | `year_to >= year_from`; both 1950–2100 |
| `market` | string | yes | Upper-case code of 2–3 letters, e.g. `EU`, `CA` |
| `aliases` | list of strings | yes | Spellings riders use |
| `last_verified` | date | yes | `YYYY-MM-DD` |
| `variant` | string | no | Only when the file covers one configuration; at most 80 characters |
| `provenance` | mapping | no | Reserved, see below |

Unknown keys are reported. `embeddings/ingest_motorcycle_knowledge.py` requires the same keys.

## Sections

All sections are level-2 headings (`## `), each appearing once. Seven are **required** because
every file in the corpus has them, and they must appear in this relative order:

1. High-value fact index
2. Periodic maintenance framework (contains `### Major recurring maintenance facts`, read by `embeddings/motorcycle_facts.py`)
3. Cooling system
4. Tires and wheels
5. Fuse reference
6. Storage
7. Expanded practical maintenance and diagnostics

Every other heading found in the corpus is **optional** (present in only some files, and worded
differently between model families); see `SECTIONS` in the schema for the list. Optional sections
may appear anywhere and are not order-checked. Unrecognised headings are allowed; the list is
documentation of current usage, not a whitelist.

## Units

| Quantity | Allowed units |
|---|---|
| distance | `km`, `mi` |
| volume | `L` |
| pressure | `kPa`, `psi` |
| torque | `N·m` |
| length | `mm` |
| time | `months` |

## Unknown values

Write exactly `not captured as an exact model-year value`. Do not leave a cell empty, write
`n/a`/`TBD`, estimate, or copy a value from another model year or variant.

## Provenance

Provenance is recorded outside the knowledge files, in `knowledge/evidence/` (see
[knowledge-verification.md](knowledge-verification.md)); knowledge files stay plain technical records. The
reserved `provenance` front matter key and inline `<!-- src:... -->` comments remain valid but are not used.

## Example skeleton

```markdown
---
manufacturer: Yamaha
model: Example Model
year_from: 2024
year_to: 2024
market: EU
aliases:
  - ExampleModel
last_verified: 2026-01-31
---

# Example Model 2024

## High-value fact index

| Fact | Value |
|---|---|
| Fuel tank | not captured as an exact model-year value |

## Periodic maintenance framework

### Major recurring maintenance facts

- Engine oil: first service at 1,000 km, then every 10,000 km.

## Cooling system

## Tires and wheels

## Fuse reference

## Storage

## Expanded practical maintenance and diagnostics
```
