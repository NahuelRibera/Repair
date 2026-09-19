"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useAuth, loginUrl } from "@/lib/AuthProvider";
import { savePendingBikeSelection } from "@/lib/pendingBikeSelection";
import type { GarageVehicle, MotoSessionDetail } from "@/lib/types";
import { BikePicker } from "./BikePicker";

export function LandingPicker() {
  const router = useRouter();
  const { user, loading } = useAuth();
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function selectBike(modelId: number, year: number) {
    setStarting(true);
    setError(null);

    // Manufacturer/model/year selection itself is public (BikePicker's
    // own catalog lookups need no auth), but creating a garage vehicle
    // and opening a conversation does. A visitor who isn't signed in yet
    // gets sent to sign in first, with the exact bike they picked
    // preserved so it's used automatically once they land back — see
    // docs/authentication.md "landing bike selector before login".
    if (!loading && !user) {
      savePendingBikeSelection({ modelId, year });
      window.location.href = loginUrl("/chat");
      return;
    }

    try {
      // Normal "choose your bike" flow — reuse an existing garage vehicle
      // for this exact manufacturer/model/year if the rider already has
      // one, rather than creating a duplicate physical motorcycle.
      const vehicle = await api.post<GarageVehicle>("/api/garage/vehicles", { modelId, year, allowDuplicate: false });
      const detail = await api.post<MotoSessionDetail>("/api/moto-sessions", { garageVehicleId: vehicle.id });
      router.push(`/chat/${detail.session.id}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        savePendingBikeSelection({ modelId, year });
        window.location.href = loginUrl("/chat");
        return;
      }
      setError("That bike isn't in the knowledge base yet. Try a different year.");
      setStarting(false);
    }
  }

  return (
    <div className={starting ? "opacity-60 pointer-events-none" : ""}>
      <BikePicker onSelected={selectBike} submitLabel="Start with this bike →" />
      {error && <p className="text-red-300 text-xs mt-3">{error}</p>}
    </div>
  );
}
