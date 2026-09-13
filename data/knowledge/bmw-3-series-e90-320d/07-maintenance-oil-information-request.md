---
id: maintenance-oil-information-request
title: Answering an oil/maintenance question without a verified spec sheet
language: en
provenance: synthetic_demo
evidence_scope: generic
systems: [maintenance]
applicability: model_wide
applies_to_manufacturer: BMW
applies_to_model: BMW 3 Series Sedan
source_url:
source_retrieved_at:
review_status: draft
---

# Answering an oil/maintenance question without a verified spec sheet

This project does not have a verified, source-checked oil specification
(grade, exact capacity, or service interval) for the BMW 3 Series (E90)
320d, and it will not state one as fact. This document exists so the
assistant has an honest way to handle "what oil does it take?" or "when is
the next service?" style questions rather than guessing a plausible-sounding
number.

## What is safe to say generically

- Diesel engines from this era, including the N47, typically specify a
  low-SAPS (low ash) oil meeting a specific manufacturer approval (BMW uses
  its own "Longlife" approval numbers for this). The exact approval number
  and viscosity grade for a specific model year and market genuinely does
  matter — using the wrong approval-level oil on a diesel with a
  particulate filter can shorten the filter's service life — so this is a
  case where "check the owner's handbook or filler cap" is the responsible
  answer, not a guess.
- Oil change intervals on cars with BMW's "Condition Based Service" system
  (used across this era) are condition-based rather than a fixed mileage —
  the car itself calculates a recommended interval from driving style and
  conditions, shown on the dashboard/service display, rather than a single
  fixed number that applies to every car of this model.
- The engine oil filler cap and the dipstick (where fitted) usually state
  the minimum required specification directly, which is the single most
  reliable source available to an owner without a manual to hand.

## What this assistant should not do

- State a specific number of liters/quarts for this car's oil capacity.
- State a specific BMW Longlife approval number (e.g. claim it "is"
  LL-01 or LL-04) without a verified source, since using the wrong one has
  real consequences for the diesel particulate filter.
- State a fixed mileage/time interval as if it applies uniformly, when the
  vehicle's own condition-based system is what actually determines it.

## What a good answer looks like

A good answer to "what oil does my 320d take?" acknowledges the genuine
question, explains why an exact number isn't being invented, and points the
owner to a source that will actually have the verified figure for their
specific car (the filler cap, the owner's handbook, or the dashboard
service menu) — rather than either refusing to engage or fabricating a
confident-sounding but unverified number.
