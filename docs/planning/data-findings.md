# Data Findings (Phase 1 Inventory)

Verified locally on 2026-09-12 against the actual files in `data/raw/` (git-ignored,
never committed). This document records facts checked directly against the raw
inputs, not assumptions.

## 1. CSV inputs (`data/raw/vehicles/`)

- 107 items total: 106 `manufacturer_id_<N>.csv` files plus one `data.numbers`
  file (Apple Numbers spreadsheet, a zip container — not a CSV, not parsed by
  the ingestion pipeline; kept as an unprocessed source artifact).
- The 106 CSV files cover manufacturer source IDs **8 through 113 contiguously**,
  no gaps, no duplicate IDs.
- Every CSV shares the exact same 43-column header, matching the spec in the
  master prompt exactly, e.g. `manufacturer_id, model_name, version,
  year_start, year_end, fuel, ... max_power`. Dimensions are already split into
  `length, width, height, ground_clearance, wheelbase, front_rear_track`
  rather than one combined `dimensions` field.
- Each row already carries a `manufacturer_id` matching its filename.
- Rows have no manufacturer-name column; the brand name only appears as the
  leading token(s) of `model_name` (e.g. `AUDI R8 GT`, `AC Ace`). Per the
  master prompt this must **not** be used as the runtime identity signal —
  see the manufacturer reconciliation section below for how identity is
  actually established.

## 2. Legacy SQL dump (`data/raw/legacy-database/repair_db_legacy.sql`)

- 17.8 MB, produced by `pg_dump 17.0` from PostgreSQL 15.9 (`SET
  transaction_timeout = 0;` is a 17.x-only GUC that older servers reject —
  restore only into PostgreSQL 17.x, never edit the dump to work around an
  older server).
- Contains only `CREATE TABLE` / `CREATE SEQUENCE` / `ALTER TABLE ... DEFAULT`
  / `COPY ... FROM stdin` / `ALTER TABLE ... ADD CONSTRAINT` / `setval`
  statements. No `DROP`, `GRANT`, functions, triggers, extensions, `COPY TO
  PROGRAM`, or `DO` blocks — confirmed by direct grep. Safe to restore
  as-is into an isolated container.
- Row counts confirmed by parsing the dump text directly (no server needed):
  - `manufacturers`: **113 rows** (matches spec).
  - `models`: **29,263 rows**, 45 columns (43 CSV fields + `model_id` +
    `manufacturer_id`, since `model_name` and columns line up 1:1 with the
    CSV headers plus an `observations` free-text column the CSVs don't have).
  - `info_sections`: **0 rows** (matches spec).
  - `info_section_images`: **0 rows** (matches spec).

## 3. Manufacturer identity reconciliation (the key finding)

This required inspecting actual row content, not just filenames — exactly the
kind of "don't infer identity from a filename ID" case the master prompt
warns about.

- **IDs 1–7 in the dump exist only in the dump — there is no corresponding
  CSV file for them.** They are: `1 Alpine, 2 Volkswagen, 3 Seat, 4 Audi,
  5 BMW, 6 Citroën, 7 Dacia`.
- **IDs 8–113 in the dump correspond 1:1 with `manufacturer_id_8.csv` …
  `manufacturer_id_113.csv`.** Row counts match exactly per manufacturer
  (e.g. dump id 8 "AC" has 10 model rows, `manufacturer_id_8.csv` has 10 data
  rows; dump id 113 "Zenvo" has 5, `manufacturer_id_113.csv` has 5).
- **Audi is duplicated in the dump under id 4 and id 15**, both with exactly
  1,995 model rows. Direct row-by-row comparison (matching `model_name` +
  `version`) shows they are **the same underlying scraped rows in two
  different text-formatting conventions**:
  - id 4 rows use raw scrape formatting: real SQL `NULL` (`\N` in the dump)
    and literal embedded newlines inside multi-value fields, e.g.
    `power = "442.8 KW @ - RPM\n602 HP @ - RPM\n594 BHP @ - RPM"`.
  - id 15 rows use cleaned formatting: the literal text string `NULL`
    instead of a real null in some fields, and a single space instead of an
    embedded newline joining multi-value fields, e.g.
    `power = "442.8 KW @ - RPM 602 HP @ - RPM 594 BHP @ - RPM"`.
  - `manufacturer_id_15.csv` matches the id-15 (cleaned) formatting exactly.
- **Conclusion used by the importer:** id 15 / the CSV file is the canonical,
  reorganized Audi namespace (this matches the owner's account of having
  reorganized the scraped data with SQL/pgAdmin). Dump id 4 is recorded as a
  duplicate legacy source pointing at the same canonical Audi manufacturer —
  its rows are linked for lineage in `raw_vehicle_records` but are **not**
  turned into a second set of canonical `vehicle_variants`, so Audi is not
  double-counted.
- **BMW (id 5) has no CSV file and no duplicate id** — its only source is the
  dump, in raw/legacy formatting. Same for Alpine, Volkswagen, Seat, Citroën,
  Dacia (ids 1, 2, 3, 6, 7).
- No other manufacturer name repeats in the 113-row `manufacturers` table.

Practical effect on ingestion: the Python CLI has two source-namespace
readers — `csv` (ids 8–113, cleaned formatting) and `legacy_sql` (ids 1–7 and
the id-4 Audi duplicate, raw formatting with real nulls/newlines) — feeding
the same staging table, with an explicit, human-reviewed
`manufacturer_source_map` (namespace + source id → canonical manufacturer)
rather than any per-row filename/name-token inference.

## 3b. CSV-to-dump brand alias check (ids 8–113)

Checked every CSV file's `model_name` brand prefix against the dump's
`manufacturer_name` for the same id, across all 106 files. 103 of 106 line up
directly; three real aliasing/edge cases were found and are recorded in
`manufacturer_source_map` rather than silently normalized away:

- **id 28**: dump canonical name is `DMC` (DeLorean Motor Company); CSV rows
  spell the brand as `DeLorean` (e.g. `DeLorean DMC-12`). Same manufacturer,
  two names for the same company — canonical name kept as `DMC`, `DeLorean`
  recorded as an alias.
- **id 41 (Holden)**: both the CSV (`manufacturer_id_41.csv`, header row
  only) and the dump have **zero** model rows. Holden is a legitimate
  manufacturer with no vehicle data in this dataset at all — not a bug, and
  not treated as a missing/broken source.
- **id 99**: dump canonical name is `Škoda` (with caron); CSV rows spell it
  ASCII-only as `SKODA`. Canonical name keeps the diacritic; `SKODA` recorded
  as an alias.

## 4. Sample data quality observations (not yet exhaustive)

- `manufacturer_id_8.csv` (AC) contains two `AC Frua Coupe` variants that are
  byte-identical except for stated gearbox ("3-speed automatic" text
  alongside a "4MT" in the version name) — flagged as a `data_quality_issue`
  candidate for manual review, not auto-corrected.
- Multi-value fields (`power`, `torque`) bundle kW/HP/BHP or lb-ft/Nm in one
  string; the importer preserves the raw string and extracts typed values
  per unit into `vehicle_specs`, never conflating HP with BHP.
- `year_end` is frequently empty/`NULL`; treated as "unknown/open-ended", not
  coerced to the current year.

## 5. Reference images

All four PNGs in `docs/reference-images/` were opened and inspected
(1672×941 RGB): `home.png`, `chat-1.png`, `chat-2.png`, `features.png`. They
show a Spanish-language "Repair" product with a car AND motorcycle picker
(BMW Serie 3 (E90) 320d is the worked example car). Design language reused
for the English car-only rebuild: dark navy/near-black hero and sidebar,
white/light chat canvas, electric-blue primary buttons, wrench mark logo,
rounded cards with subtle borders. Motorcycle picker, pricing tier, blog,
login/account chrome, and all Spanish copy are dropped per the master
prompt's scope (cars only, no pricing/login/blog, English only).

## 5b. Systematic issue found by the importer: version-name vs power-field mismatch

After running the full importer (`ingest.quality_rules.check_version_name_power_mismatch`),
16,951 of 27,194 canonical variants (62%) have a `version` label whose
parenthesized HP figure disagrees with the HP parsed from the `power` field
by more than 5%. This is not noise from the parser: the BMW E90 320d row
used as this project's own demo vehicle is itself affected —

```
version: "BMW 3 Series (E90) 320d 6MT RWD (177 HP)"
power:   "85 KW @ 4000 RPM 116 HP @ 4000 RPM 114 BHP @ 4000 RPM"
```

Spot-checking 15 random mismatches shows the same `power` figure (e.g. 116
HP) recurring across multiple different-year/different-labeled-HP versions
of what is otherwise the same engine family — consistent with the original
scraper having copy-pasted one reference row's numeric `power`/`torque`/
`top_speed` block across several sibling listings that only actually differ
by name/year, rather than re-scraping the numeric spec block per listing.
Practical effect: the `version` label's stated HP is treated as the more
reliable figure for display purposes; the `power` field's derived
`power_hp`/`power_kw`/`power_bhp` values are kept (never silently dropped —
they may still be correct for some rows) but every affected row is flagged
in `data_quality_issues` with `rule = 'version_name_power_mismatch'`, and no
document in the knowledge corpus states a specific horsepower figure for the
BMW E90 320d demo vehicle for exactly this reason.

## 6. Reference implementation vehicle

BMW Serie 3 (E90), 320d, 2008, confirmed as the reference example in the
design images. Its exact presence/spec in this dataset (dump id 5, raw
formatting) is verified during Python ingestion in Phase 1 before the
knowledge corpus is written against it.

## 7. Generation/engine-specific documents must not link model-wide

`vehicle_models.model_name` is not generation-specific: "BMW 3 Series
Sedan" alone covers 277 variants spanning the E46 through G20 generations
(1998–present, petrol and diesel). A knowledge document whose content
names a specific engine (e.g. `04-bmw-e90-320d-n47-known-issues.md`,
which is specifically about the E90-generation N47 diesel) was originally
linked with `applicability: model_wide`, which would have made it
retrievable for an unrelated variant such as a 2020 G20 330i petrol — an
incompatible-vehicle leak.

Fixed by adding an optional `applies_to_variant_name_contains` frontmatter
field (see `pipelines/embeddings/ingest_knowledge.py::_link_vehicle`):
when present, the ingester links every `vehicle_variants` row under the
resolved model whose `variant_name` contains the given substring as an
individual `exact` link, instead of one `model_wide` link. Doc 04 now
resolves to exactly the 13 real `(E90) 320d` variants in the catalogue.
`data/fixtures/legacy_dump_fixture.sql` was extended with a real G20 330i
row (same model_name as the E90 fixture row, different generation) so this
exclusion is covered by an automated test
(`test_link_vehicle_variant_name_pattern_excludes_other_generations`).
