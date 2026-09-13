"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import type { SessionDetail } from "@/lib/types";
import { useDemoVehicle } from "@/lib/useDemoVehicle";
import { VehiclePicker } from "./VehiclePicker";

export function LandingPicker({ examples }: { examples: string[] }) {
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
    // The demo vehicle is resolved once on mount by useDemoVehicle (the
    // shared source of truth — see lib/useDemoVehicle.ts) rather than
    // re-fetched here, so every example button opens the same, correct
    // variant the "Supported demo vehicle" panel describes.
    if (demo.status !== "available") {
      router.push("/chat");
      return;
    }
    await startWithVariant(demo.vehicle.variant.id, prefill);
  }

  return (
    <div className={starting ? "opacity-60 pointer-events-none" : ""}>
      <VehiclePicker onVariantSelected={(id) => startWithVariant(id)} />
      <p className="text-white/40 text-xs text-center my-4">or try an example question</p>
      <div className="space-y-2">
        {examples.map((example) => (
          <button
            key={example}
            type="button"
            onClick={() => startWithExample(example)}
            className="w-full text-left rounded-lg border border-white/10 bg-white/5 px-3.5 py-2.5 text-sm text-white/80 hover:bg-white/10 hover:text-white transition-colors"
          >
            {example}
          </button>
        ))}
      </div>
    </div>
  );
}
