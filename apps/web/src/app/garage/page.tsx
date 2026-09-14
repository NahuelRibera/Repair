"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { bikeTitle } from "@/lib/types";
import type { GarageVehicle } from "@/lib/types";
import { BikePicker } from "@/components/BikePicker";

export default function GaragePage() {
  const [garage, setGarage] = useState<GarageVehicle[] | null>(null);
  const [addingBike, setAddingBike] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function refresh() {
    api.get<GarageVehicle[]>("/api/garage/vehicles").then(setGarage);
  }

  useEffect(refresh, []);

  async function addBike(modelId: number, year: number) {
    setError(null);
    try {
      await api.post<GarageVehicle>("/api/garage/vehicles", { modelId, year });
      setAddingBike(false);
      refresh();
    } catch {
      setError("That bike isn't in the knowledge base yet, or something went wrong. Please try another year.");
    }
  }

  if (garage === null) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-muted">Loading your garage…</p>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 py-10">
        <h1 className="text-2xl font-bold mb-1">My Garage</h1>
        <p className="text-muted mb-8">Your motorcycles, their maintenance history, and what's due next.</p>

        {garage.length === 0 && !addingBike && (
          <div className="rounded-xl border border-dashed border-border p-8 text-center mb-6">
            <p className="text-sm text-muted mb-4">You haven&apos;t added a motorcycle yet.</p>
            <button
              type="button"
              onClick={() => setAddingBike(true)}
              className="rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white hover:bg-accent-hover transition-colors"
            >
              Add your first bike
            </button>
          </div>
        )}

        {garage.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
            {garage.map((vehicle) => (
              <Link
                key={vehicle.id}
                href={`/garage/${vehicle.id}`}
                className="rounded-xl border border-border bg-panel p-5 hover:border-accent/40 hover:bg-accent/5 transition-colors"
              >
                <p className="font-semibold mb-0.5">{vehicle.nickname || bikeTitle(vehicle)}</p>
                <p className="text-sm text-muted mb-3">
                  {bikeTitle(vehicle)} · {vehicle.year}
                </p>
                <p className="text-sm">
                  {vehicle.currentOdometerKm != null
                    ? `${Math.round(vehicle.currentOdometerKm).toLocaleString()} km`
                    : "Odometer not recorded"}
                </p>
              </Link>
            ))}
          </div>
        )}

        {error && <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 mb-4 text-sm text-red-700">{error}</div>}

        {garage.length > 0 && !addingBike && (
          <button
            type="button"
            onClick={() => setAddingBike(true)}
            className="w-full rounded-lg border border-dashed border-border px-4 py-3.5 text-sm font-medium text-muted hover:border-accent/40 hover:text-accent transition-colors"
          >
            + Add another bike
          </button>
        )}

        {addingBike && (
          <div className="rounded-xl border border-border bg-panel p-5">
            <p className="font-semibold mb-3">Add a motorcycle</p>
            <BikePicker onSelected={addBike} submitLabel="Add to my garage" />
            <button
              type="button"
              onClick={() => setAddingBike(false)}
              className="mt-3 text-sm text-muted hover:text-foreground"
            >
              Cancel
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
