# Knowledge file schema

Schema version: **1.0**. The machine-readable definition is
[`pipelines/knowledge/schema.py`](../pipelines/knowledge/schema.py) (`load_schema()` returns it as
plain data). The schema describes what the current corpus under `knowledge/motorcycles/` already
uses; it does not require rewriting any file.

Validate a file with `knowledge.schema.validate_file(path)`; it returns a list of problems (missing
front matter or sections, invalid identifiers, a year range that disagrees with the file name,
duplicate headings, out-of-order required sections). `pipelines/tests/test_knowledge_schema.py`
runs it over the whole corpus.

## Layout

`knowledge/motorcycles/<manufacturer-slug>/<model-slug>/<year_from>.md`. Slugs are produced by
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

## Provenance (reserved)

Per-value provenance is not recorded yet. It can be added without breaking the format through an
optional `provenance` front matter mapping (fact label to source id) and/or an inline
`<!-- src:<source-id> -->` comment after a value. Files without either stay valid.

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
