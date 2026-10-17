"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { bikeTitle } from "@/lib/types";
import type { GarageVehicle } from "@/lib/types";
import { BikePicker } from "@/components/BikePicker";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { ErrorState, LoadingState } from "@/components/PageState";

export default function GaragePage() {
  const [garage, setGarage] = useState<GarageVehicle[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [addingBike, setAddingBike] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  function refresh() {
    api.get<GarageVehicle[]>("/api/garage/vehicles").then(
      (vehicles) => {
        setLoadError(false);
        setGarage(vehicles);
      },
      () => setLoadError(true),
    );
  }

  function retry() {
    setLoadError(false);
    refresh();
  }

  useEffect(refresh, []);

  async function confirmDelete(id: number) {
    setDeleteError(null);
    try {
      await api.del(`/api/garage/vehicles/${id}`);
      setPendingDeleteId(null);
      setGarage((current) => current?.filter((v) => v.id !== id) ?? current);
      // Sidebar's own conversation list only re-fetches on chat route
      // changes — this tells it to refresh so chats belonging to the
      // deleted bike disappear without a full page reload.
      window.dispatchEvent(new Event("repair:garage-vehicle-deleted"));
    } catch {
      setPendingDeleteId(null);
      setDeleteError("Couldn't delete that motorcycle. Please try again.");
    }
  }

  const pendingDeleteVehicle = garage?.find((v) => v.id === pendingDeleteId) ?? null;

  async function addBike(modelId: number, year: number) {
    setError(null);
    try {
      // Explicit "add a bike" flow — always create a new garage vehicle,
      // even if an identical manufacturer/model/year already exists
      // (a rider may genuinely own two of the same bike).
      await api.post<GarageVehicle>("/api/garage/vehicles", { modelId, year, allowDuplicate: true });
      setAddingBike(false);
      refresh();
    } catch {
      setError("That bike isn't in the knowledge base yet, or something went wrong. Please try another year.");
    }
  }

  if (garage === null) {
    return loadError ? (
      <ErrorState message="Couldn't load your garage." onRetry={retry} />
    ) : (
      <LoadingState label="Loading your garage…" />
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 py-10">
        <h1 className="text-2xl font-bold mb-1">My Garage</h1>
        <p className="text-muted mb-8">Your motorcycles, their maintenance history, and what&apos;s due next.</p>

        {loadError && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 mb-4 text-sm text-red-700" role="alert">
            <p>Couldn&apos;t refresh your garage.</p>
            <button type="button" onClick={retry} className="mt-1 font-medium underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent">
              Try again
            </button>
          </div>
        )}

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
          <ul className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6" aria-label="Your motorcycles">
            {garage.map((vehicle) => (
              <li key={vehicle.id} className="group relative min-w-0">
                <Link
                  href={`/garage/${vehicle.id}`}
                  className="block rounded-xl border border-border bg-panel p-5 pr-10 break-words hover:border-accent/40 hover:bg-accent/5 transition-colors"
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
                <button
                  type="button"
                  aria-label={`Delete ${bikeTitle(vehicle)} ${vehicle.year}`}
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    setPendingDeleteId(vehicle.id);
                  }}
                  className="absolute right-1.5 top-1.5 rounded-md p-2.5 text-muted/60 sm:opacity-0 group-hover:opacity-100 hover:bg-black/5 hover:text-red-600 focus-visible:opacity-100 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent transition-opacity"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                  </svg>
                </button>
              </li>
            ))}
          </ul>
        )}

        {error && <div role="alert" className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 mb-4 text-sm text-red-700">{error}</div>}
        {deleteError && <div role="alert" className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 mb-4 text-sm text-red-700">{deleteError}</div>}

        {garage.length > 0 && !addingBike && (
          <button
            type="button"
            onClick={() => setAddingBike(true)}
            className="w-full rounded-lg border border-dashed focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent border-border px-4 py-3.5 text-sm font-medium text-muted hover:border-accent/40 hover:text-accent transition-colors"
          >
            + Add another bike
          </button>
        )}

        {addingBike && (
          <div className="rounded-xl border border-border bg-panel p-5">
            <h2 className="font-semibold mb-3">Add a motorcycle</h2>
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

      <ConfirmDialog
        open={pendingDeleteVehicle !== null}
        title="Delete this motorcycle?"
        description="This will permanently remove the motorcycle and its maintenance history, saved preferences and associated conversations."
        confirmLabel="Delete motorcycle"
        onConfirm={() => pendingDeleteVehicle && confirmDelete(pendingDeleteVehicle.id)}
        onCancel={() => setPendingDeleteId(null)}
      />
    </div>
  );
}
