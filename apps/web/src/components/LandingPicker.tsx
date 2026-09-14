"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import type { GarageVehicle, MotoSessionDetail } from "@/lib/types";
import { BikePicker } from "./BikePicker";

export function LandingPicker() {
  const router = useRouter();
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function selectBike(modelId: number, year: number) {
    setStarting(true);
    setError(null);
    try {
      const vehicle = await api.post<GarageVehicle>("/api/garage/vehicles", { modelId, year });
      const detail = await api.post<MotoSessionDetail>("/api/moto-sessions", { garageVehicleId: vehicle.id });
      router.push(`/chat/${detail.session.id}`);
    } catch {
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
