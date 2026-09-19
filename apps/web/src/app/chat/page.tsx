"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { bikeTitle } from "@/lib/types";
import type { GarageVehicle, MotoSessionDetail } from "@/lib/types";
import { BikePicker } from "@/components/BikePicker";
import { takePendingBikeSelection } from "@/lib/pendingBikeSelection";

export default function NewChatPage() {
  const router = useRouter();
  const [garage, setGarage] = useState<GarageVehicle[] | null>(null);
  const [addingBike, setAddingBike] = useState(false);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const consumedPendingSelection = useRef(false);

  useEffect(() => {
    api
      .get<GarageVehicle[]>("/api/garage/vehicles")
      .then(setGarage)
      .catch(() => setGarage([]));
  }, []);

  // The bike a visitor picked on the public landing page before being
  // sent to sign in (see LandingPicker) — consumed exactly once, the
  // first time this page renders as an authenticated user, then acted on
  // automatically so the rider doesn't have to re-pick it.
  useEffect(() => {
    if (garage === null || consumedPendingSelection.current) return;
    consumedPendingSelection.current = true;
    const pending = takePendingBikeSelection();
    if (pending) {
      addBikeAndStart(pending.modelId, pending.year);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [garage]);

  async function startWithGarageVehicle(garageVehicleId: number) {
    setStarting(true);
    setError(null);
    try {
      const detail = await api.post<MotoSessionDetail>("/api/moto-sessions", { garageVehicleId });
      router.push(`/chat/${detail.session.id}`);
    } catch {
      setError("Couldn't start a conversation. Please try again.");
      setStarting(false);
    }
  }

  async function addBikeAndStart(modelId: number, year: number) {
    setStarting(true);
    setError(null);
    try {
      // Normal "choose your bike" flow — reuse an existing garage vehicle
      // for this exact manufacturer/model/year if the rider already has
      // one, rather than creating a duplicate physical motorcycle.
      const vehicle = await api.post<GarageVehicle>("/api/garage/vehicles", { modelId, year, allowDuplicate: false });
      await startWithGarageVehicle(vehicle.id);
    } catch {
      setError("That bike isn't in the knowledge base yet, or something went wrong. Please try another year.");
      setStarting(false);
    }
  }

  if (garage === null) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-muted">Loading your garage…</p>
      </div>
    );
  }

  const showPicker = addingBike || garage.length === 0;

  return (
    <div className={`flex-1 min-h-0 overflow-y-auto overscroll-contain ${starting ? "opacity-60 pointer-events-none" : ""}`}>
      <div className="mx-auto max-w-2xl px-4 py-10 sm:py-16">
        <p className="text-xs font-semibold tracking-[0.2em] text-accent text-center mb-3">
          MAINTENANCE · OWNERSHIP · TROUBLESHOOTING
        </p>
        <h1 className="text-2xl sm:text-3xl font-bold text-center mb-2">
          {showPicker ? "Choose your bike" : "What do you want to know about your bike?"}
        </h1>
        <p className="text-muted text-center mb-8">
          {showPicker
            ? "Manufacturer, model and year. Repair uses only verified knowledge for this exact bike."
            : "Pick a bike from your garage to start, or add another one."}
        </p>

        {error && (
          <div className="rounded-lg bg-red-50 border border-red-200 px-4 py-3 mb-4 text-sm text-red-700">{error}</div>
        )}

        {showPicker ? (
          <div className="rounded-xl border border-border bg-panel p-5">
            <BikePicker onSelected={addBikeAndStart} submitLabel="Start chatting →" />
            {garage.length > 0 && (
              <button
                type="button"
                onClick={() => setAddingBike(false)}
                className="mt-3 text-sm text-muted hover:text-foreground"
              >
                ← Back to my garage
              </button>
            )}
          </div>
        ) : (
          <div className="space-y-2.5">
            {garage.map((vehicle) => (
              <button
                key={vehicle.id}
                type="button"
                onClick={() => startWithGarageVehicle(vehicle.id)}
                className="w-full flex items-center justify-between rounded-lg border border-border bg-panel px-4 py-3.5 text-left hover:border-accent/40 hover:bg-accent/5 transition-colors"
              >
                <div>
                  <p className="text-sm font-semibold">{vehicle.nickname || bikeTitle(vehicle)}</p>
                  <p className="text-xs text-muted">
                    {bikeTitle(vehicle)} · {vehicle.year}
                    {vehicle.currentOdometerKm != null ? ` · ${Math.round(vehicle.currentOdometerKm).toLocaleString()} km` : ""}
                  </p>
                </div>
                <span className="text-accent text-sm font-medium">Chat →</span>
              </button>
            ))}
            <button
              type="button"
              onClick={() => setAddingBike(true)}
              className="w-full rounded-lg border border-dashed border-border px-4 py-3.5 text-sm font-medium text-muted hover:border-accent/40 hover:text-accent transition-colors"
            >
              + Add another bike
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
