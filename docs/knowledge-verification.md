# Knowledge verification

A knowledge file can be well formed and still wrong. Repair checks the corpus in two separate layers:

| Layer | Tool | Question | Fails the build when |
|---|---|---|---|
| Structural | `python -m knowledge.validate` | Is the file in the schema (front matter, sections, units, no duplicate or overlapping entries)? | any error not in the baseline |
| Technical | `python -m knowledge.technical` | Do recorded sources support the values, for this exact motorcycle, model year and market? Are written conversions and capacities consistent? | a claim conflicts with its file or its sources |

Passing the structural layer never makes a file technically verified.

## Evidence

Knowledge files hold no source names, URLs or document references. Provenance lives beside them in
`knowledge/evidence/`:

- `sources.yaml`: one entry per document read, with its kind, publisher, document number and edition,
  the motorcycles, model years and markets it applies to, and the SHA-256 of the copy that was read. Only
  facts are recorded, never passages.
- `motorcycles/<manufacturer>/<model>/<stem>.yaml`, mirroring `knowledge/motorcycles/.../<stem>.md`
  (`staging/motorcycles/...` mirrors `knowledge/staging/motorcycles/...`). It holds claims: a fact label as
  written in the knowledge file (`also` lists other labels for the same fact), the value, and for each source
  the location and the value as that source states it.

A source **confirms** a claim only when it applies to the file's manufacturer and model, covers every model
year of the file, and lists the file's market. A source for another market or year is recorded and reported
as a research lead, but confirms nothing: the 2021 Ténéré 700 owner's manuals check valve clearance every
40000 km (EU edition) and every 42000 km (US edition), and give a minimum tread depth of 1.6 mm and 1.0 mm.

Safety-critical values (torques, tire pressures, brake fluid, valve clearance, chain slack) are confirmed
only by an official document: an owner's manual, a service manual or a manufacturer specification.

## Status of a file

| Status | Meaning |
|---|---|
| `technically_verified` | Evidence exists, nothing conflicts, and every critical value the file states (torques, tire pressures, oil and coolant capacities and intervals, spark plug, valve clearance, brake fluid, chain slack) is confirmed. |
| `partially_evidenced` | At least one value is confirmed, not all critical ones. |
| `unverified` | No value is confirmed yet. Most of the corpus: written from owner's manuals but not yet recorded with evidence. |

`docs/knowledge-coverage.md` counts motorcycle profiles (manufacturer, model, market, model year) per tier:
identified (knowledge files and `knowledge/catalog.yaml`), with sources, structurally valid, partially
evidenced and technically verified. A generated file alone is not coverage.

## Consistency checks (every file)

- A value's written conversions match it: kPa / kgf/cm² / psi, N·m / kgf·m / lb·ft, L / US qt / Imp.qt.
- Oil with filter is not below oil without filter; the fuel reserve is below the tank capacity.

## Adding or enriching a motorcycle

1. Find a document that applies to the exact motorcycle, model year and market, and that may be read
   (no paywalls or logins are bypassed). Record it in `sources.yaml` with its SHA-256.
2. Write the claims in an evidence file, citing the location and the value as the document states it. Never
   carry a value over from another year, market or variant; when two documents disagree, cite only the one
   that applies and note the other.
3. For a new file, add a `profile` block and a `section` per claim (`index: true` for the fact index), then
   `python -m knowledge.draft <evidence file>`. It writes the file in Repair's own wording with every absence
   stated, and refuses a result that fails either layer. For an existing file, edit it by hand.
4. Run `python -m knowledge.validate`, `python -m knowledge.technical` and `python -m knowledge.coverage`.

## Staging

`knowledge/staging/motorcycles/` holds verified profiles that riders are not served yet; ingestion never
reads it. Today that is a second market for a model year the corpus already covers: fact and chunk retrieval
select documents by model and year only, so the `market-sibling` rule rejects such a file in
`knowledge/motorcycles/` until retrieval selects by the garage vehicle's market.
