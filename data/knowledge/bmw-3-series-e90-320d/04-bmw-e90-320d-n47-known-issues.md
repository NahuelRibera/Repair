---
id: bmw-e90-320d-n47-known-issues
title: Community-reported wear items on the E90 320d (N47 diesel engine)
language: en
provenance: public_source
evidence_scope: vehicle_specific
systems: [engine, diagnostics]
# This content names a specific engine/generation (E90-generation 320d,
# N47 diesel) — it must not attach to every BMW 3 Series Sedan variant
# ever sold (that model name spans E46 through G20, 1998-present, petrol
# and diesel). applies_to_variant_name_contains restricts the link to the
# 13 vehicle_variants rows that actually match "(E90) 320d" rather than
# using a model_wide link, so retrieval for e.g. a G20 330i cannot surface
# this document. See docs/planning/data-findings.md.
applicability: exact
applies_to_manufacturer: BMW
applies_to_model: BMW 3 Series Sedan
applies_to_variant_name_contains: "(E90) 320d"
source_url:
  - https://www.e90post.com/forums/showthread.php?t=2004604
  - https://www.bmwfanatics.co.za/threads/e90-fault-codes-help.98900/
  - https://www.pelicanparts.com/BMW/techarticles/BMW-3-Series-E90/ELEC-Reading_Fault_Codes/ELEC-Reading_Fault_Codes.htm
source_retrieved_at: 2026-09-12
review_status: draft
---

# Community-reported wear items on the E90 320d (N47 diesel engine)

**Status: unverified community reporting, not an official BMW technical
bulletin.** This document summarizes patterns repeatedly mentioned across
public BMW owner forums (e90post.com, BMWFanatics, and the general
diagnostic-code reference on Pelican Parts, all retrieved 2026-09-12 — see
`source_url`). It is included so this demo project can show what
vehicle-specific evidence looks like, including its limits: forum reports
are not a substitute for a manufacturer bulletin or a technician's
inspection, and this project has not independently verified any of the
specific claims below against BMW documentation.

## Why this matters for a 2008–2011 320d specifically

The E90-generation 320d covered by this project's demo vehicle uses BMW's
N47 four-cylinder common-rail diesel engine. Forum discussion consistently
associates a handful of symptom clusters with this engine family:

- **Timing chain wear.** Multiple threads describe the N47's timing chain
  and guides as a known wear item on higher-mileage examples, sometimes
  presenting as a rattle on cold start. This is widely repeated across
  independent threads but is anecdotal; only a physical inspection (chain
  stretch measurement) confirms it on a specific car.
- **EGR and intake system fouling.** Diesel EGR systems generally
  accumulate soot over time; forum posts describe this contributing to
  rough idle or reduced performance on higher-mileage N47 engines.
- **Vacuum/tandem pump seal wear.** Several threads describe the combined
  vacuum-and-fuel tandem pump on the N47 developing a seal leak with age,
  which can affect both the brake servo's vacuum assistance and EGR valve
  actuation at the same time — worth mentioning to a technician if a
  vehicle has both a slightly firmer brake pedal and an EGR-related code,
  since forum reports suggest these can share one root cause.
- **Turbo underboost (generic code P0299).** `P0299` is a standard,
  cross-manufacturer OBD-II code meaning the engine did not reach expected
  turbo boost pressure. It is one of the most frequently mentioned codes in
  N47 discussions. The underlying cause reported most often in these
  threads is a sticking variable-geometry turbo actuator, but a boost leak
  (hose, intercooler) or a failing boost sensor can trigger the same
  generic code — this project does not claim to know which applies to any
  specific vehicle without more evidence.

## What this document deliberately does not include

The forum sources above also reference BMW-specific hexadecimal fault codes
(distinct from the standard P-codes) for some of these faults. This
document does not repeat those, because their exact meaning depends on the
specific diagnostic tool and software version used to read them, and this
project cannot verify them against an official BMW source. If a
BMW-specific code is involved, a workshop with the correct diagnostic
software is the reliable way to confirm what it means for a given car.

## How this should be used in a diagnosis

This document supports a *hypothesis*, not a conclusion. A 320d from this
generation reporting rough idle, a cold-start rattle, or a P0299 code is
consistent with these commonly-discussed wear patterns — but so are several
unrelated, more mundane causes (a loose connector, a low-quality diesel
fill, simple sensor drift). Any answer that cites this document should
still recommend the safe checks that don't require assuming which cause
applies.
