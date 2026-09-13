"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import type { VariantDetail } from "./types";

/**
 * The single shared source of truth for "the supported demo vehicle" on
 * the frontend. Both the landing page and the in-app new-chat page use
 * this hook instead of each independently guessing — previously both
 * derived it from `/api/vehicles/covered`'s first (year-ascending) row,
 * which for "BMW 3 Series Sedan" resolved to the oldest generation linked
 * via any of the six generic, model-wide documents (e.g. an E30 318i),
 * not the curated E90 320d the knowledge corpus is actually built around.
 *
 * `/api/vehicles/demo` resolves the real variant from configured name/year
 * criteria (see DemoVehicleProperties on the API side) and 404s if it
 * can't be resolved — that 404 becomes the "unavailable" status here, so
 * the UI can show an honest message instead of silently substituting a
 * different vehicle.
 */
export type DemoVehicleState =
  | { status: "loading" }
  | { status: "available"; vehicle: VariantDetail }
  | { status: "unavailable" };

export function useDemoVehicle(): DemoVehicleState {
  const [state, setState] = useState<DemoVehicleState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    api
      .get<VariantDetail>("/api/vehicles/demo")
      .then((vehicle) => {
        if (!cancelled) setState({ status: "available", vehicle });
      })
      .catch(() => {
        if (!cancelled) setState({ status: "unavailable" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
