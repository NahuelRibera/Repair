---
id: toyota-corolla-hybrid-battery-warning
title: "[TEST FIXTURE] Hybrid system warning light, Toyota Corolla"
language: en
provenance: synthetic_demo
evidence_scope: vehicle_specific
systems: [hybrid_drive, electrical]
applicability: model_wide
applies_to_manufacturer: Toyota
applies_to_model: TOYOTA Corolla
source_url:
source_retrieved_at:
review_status: draft
---

# [TEST FIXTURE] Hybrid system warning light, Toyota Corolla

**This document exists only to test retrieval isolation.** It is not part
of the BMW 3 Series (E90) 320d demo scenarios and must never be retrieved
for a BMW-scoped conversation. It is deliberately about an unrelated
vehicle (a Toyota Corolla) and an unrelated system (hybrid drive), and is
linked in the database only to Toyota Corolla models
(`vehicle_models.id = 2047`).

## Symptom

A "check hybrid system" warning appears on the dash together with reduced
power and the engine running more than usual to maintain the hybrid
battery's charge.

## Generic hybrid-system triage (synthetic, for fixture purposes only)

- Hybrid battery state-of-charge management issues on a Corolla hybrid
  typically reduce available electric-only power rather than stopping the
  car; if the car becomes fully immobile, that is a more urgent condition.
- A stored hybrid-system code should be read with a tool capable of
  reading hybrid-specific codes, not just generic engine codes, since the
  hybrid control module is a separate system from the combustion engine's
  ECU on this platform.
- High-voltage hybrid battery packs are a safety-critical, high-voltage
  system. This fixture document does not, and a real answer must not,
  describe any hands-on inspection or repair steps for the high-voltage
  battery or its orange-colored cabling — that is exclusively a job for a
  technician trained and equipped for high-voltage systems.

## Why this document must not appear in a BMW E90 320d conversation

If an evaluation or a live conversation about the BMW 320d's window,
brakes, suspension, oil, or engine light ever retrieves this document, that
is a retrieval-isolation bug: this content is scoped to a completely
different manufacturer, model, and drivetrain, and has no legitimate
applicability to the demo vehicle.
