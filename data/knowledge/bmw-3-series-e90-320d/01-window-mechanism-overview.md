---
id: window-mechanism-overview
title: How electric window regulators work
language: en
provenance: synthetic_demo
evidence_scope: generic
systems: [electrical, doors]
applicability: model_wide
applies_to_manufacturer: BMW
applies_to_model: BMW 3 Series Sedan
source_url:
source_retrieved_at:
review_status: draft
---

# How electric window regulators work

This is general reference material about how power windows work on most
modern cars, including the BMW 3 Series (E90). It is written for this demo
project and is not sourced from a BMW workshop manual — it does not contain
any BMW-specific part numbers, fuse numbers, or wiring diagrams.

## The basic circuit

A power window system has four parts that can fail independently:

1. **The switch** — usually one master switch per door plus a single switch
   at each other door, wired back to a central point.
2. **The window control module or relay** — some vehicles route each
   window through a simple relay; many since the early 2000s use a small
   electronic control module per door that also handles anti-pinch
   detection.
3. **The motor** — a small electric motor with a gear reducer, mounted on
   the regulator mechanism inside the door.
4. **The regulator mechanism** — the scissor-arm or cable mechanism that
   converts the motor's rotation into the glass moving up and down.

A fault in any one of these can look identical from the driver's seat: the
window "does not work." Distinguishing between them is what the
troubleshooting steps in this project's companion document
(`window-troubleshooting-steps`) walk through.

## Why "no motor sound" is the single most useful symptom

If you press the switch and hear nothing at all — no motor whirring, no
clicking — the fault is almost always upstream of the motor itself: the
switch, the wiring to it, a blown fuse, or (less often) the door control
module's power supply. If you hear the motor running but the glass does not
move, the fault is downstream: the regulator mechanism itself, a stripped
gear, or a disconnected cable/arm.

This distinction is why any diagnostic conversation about a non-working
window should start by asking whether the motor can be heard at all, and
whether the fault is on one door or several — a fault present on every
door at once almost always points to a shared component (a body control
module, a shared fuse, or a driver's-door master switch that has failed and
is also feeding the other switches), while a single-door fault points to
something local to that door.

## What this document does not cover

This overview does not state BMW E90-specific fuse numbers, relay
locations, or the exact wiring color codes for the 3 Series — those vary by
production date and market, and stating a specific number here without a
verified source would be worse than not stating one. Consult the vehicle's
own owner's handbook or an official BMW workshop reference for those exact
figures.
