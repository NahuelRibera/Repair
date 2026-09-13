"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { vehicleTitle } from "@/lib/types";
import type { SessionDetail } from "@/lib/types";
import { useDemoVehicle } from "@/lib/useDemoVehicle";
import { VehiclePicker } from "@/components/VehiclePicker";

const EXAMPLES = [
  { icon: "🔧", text: "The driver's window won't go up, what could it be?" },
  { icon: "⚠️", text: "The engine warning light just came on" },
  { icon: "🛑", text: "I feel a vibration when braking" },
  { icon: "💧", text: "What oil does my car take, and how much?" },
];

export default function NewChatPage() {
  const router = useRouter();
  const demo = useDemoVehicle();
  const [starting, setStarting] = useState(false);

  async function startWithVariant(variantId: number, prefill?: string) {
    setStarting(true);
    try {
      const detail = await api.post<SessionDetail>("/api/sessions", { variantId });
      const query = prefill ? `?prefill=${encodeURIComponent(prefill)}` : "";
      router.push(`/chat/${detail.session.id}${query}`);
    } finally {
      setStarting(false);
    }
  }

  async function startWithExample(prefill: string) {
    if (demo.status !== "available") return;
    await startWithVariant(demo.vehicle.variant.id, prefill);
  }

  return (
    <div className={`flex-1 min-h-0 overflow-y-auto overscroll-contain ${starting ? "opacity-60 pointer-events-none" : ""}`}>
      <div className="mx-auto max-w-2xl px-4 py-10 sm:py-16">
        <p className="text-xs font-semibold tracking-[0.2em] text-accent text-center mb-3">
          DIAGNOSE · LEARN · REPAIR
        </p>
        <h1 className="text-2xl sm:text-3xl font-bold text-center mb-2">What can we help with today?</h1>
        <p className="text-muted text-center mb-8">
          Select your vehicle, or jump straight into a supported demo scenario below.
        </p>

        <div className="rounded-xl border border-border bg-panel p-5 mb-6">
          <VehiclePicker onVariantSelected={(id) => startWithVariant(id)} />
        </div>

        {demo.status === "available" && (
          <>
            <div className="rounded-lg bg-accent/5 border border-accent/20 px-4 py-3 mb-4 text-sm">
              <p className="font-medium text-accent mb-0.5">Supported demo vehicle</p>
              <p className="text-muted">
                The {vehicleTitle(demo.vehicle.manufacturerName, demo.vehicle.modelName)} (
                {demo.vehicle.variant.variantName}) has full scenario coverage with cited evidence. Other vehicles
                in the catalogue can still be selected and browsed, but chat will honestly report insufficient
                evidence rather than fabricating vehicle-specific documentation for them.
              </p>
            </div>
            <p className="text-xs font-medium text-muted mb-2 text-center">or try an example question</p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5">
              {EXAMPLES.map((ex) => (
                <button
                  key={ex.text}
                  type="button"
                  onClick={() => startWithExample(ex.text)}
                  className="flex items-start gap-2.5 text-left rounded-lg border border-border bg-panel px-3.5 py-3 text-sm hover:border-accent/40 hover:bg-accent/5 transition-colors"
                >
                  <span aria-hidden>{ex.icon}</span>
                  <span>{ex.text}</span>
                </button>
              ))}
            </div>
          </>
        )}

        {demo.status === "unavailable" && (
          <div className="rounded-lg bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-900">
            The configured demo vehicle isn&apos;t available in the catalogue right now. You can still select any
            vehicle above — chat will honestly report insufficient evidence where no documentation exists yet.
          </div>
        )}
      </div>
    </div>
  );
}
