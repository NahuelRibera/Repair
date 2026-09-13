"use client";

import { useCallback, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Manufacturer, PageResult, VehicleModel, VehicleVariant } from "@/lib/types";
import { Combobox, type ComboboxOption } from "./Combobox";

interface VehiclePickerProps {
  onVariantSelected: (variantId: number) => void;
}

// Comfortably above the real catalogue's current maximums (112
// manufacturers total, ~110 models for the largest single manufacturer —
// see docs/planning/data-findings.md) so opening either dropdown with no
// search text fetches the complete, truly browsable list in one request,
// not just an arbitrary first page. Matches CatalogueController's
// MAX_PAGE_SIZE, which is the actual server-side bound.
const BROWSE_SIZE = 500;

export function VehiclePicker({ onVariantSelected }: VehiclePickerProps) {
  const [manufacturer, setManufacturer] = useState<ComboboxOption | null>(null);
  const [model, setModel] = useState<ComboboxOption | null>(null);
  const [variants, setVariants] = useState<VehicleVariant[]>([]);
  const [year, setYear] = useState<number | null>(null);
  const [variantId, setVariantId] = useState<number | null>(null);
  const [loadingVariants, setLoadingVariants] = useState(false);
  const variantsRequestGuard = useRef(0);

  const fetchManufacturers = useCallback(async (search: string): Promise<ComboboxOption[]> => {
    const res = await api.get<PageResult<Manufacturer>>(
      `/api/manufacturers?search=${encodeURIComponent(search)}&size=${BROWSE_SIZE}`
    );
    return res.items.map((m) => ({ id: m.id, label: m.name }));
  }, []);

  const fetchModels = useCallback(
    async (search: string): Promise<ComboboxOption[]> => {
      if (!manufacturer) return [];
      const res = await api.get<PageResult<VehicleModel>>(
        `/api/manufacturers/${manufacturer.id}/models?search=${encodeURIComponent(search)}&size=${BROWSE_SIZE}`
      );
      return res.items.map((m) => ({ id: m.id, label: m.modelName }));
    },
    [manufacturer]
  );

  function selectManufacturer(opt: ComboboxOption) {
    setManufacturer(opt);
    setModel(null);
    setVariants([]);
    setYear(null);
    setVariantId(null);
  }

  async function selectModel(opt: ComboboxOption) {
    setModel(opt);
    setYear(null);
    setVariantId(null);
    setVariants([]);
    setLoadingVariants(true);
    const requestId = ++variantsRequestGuard.current;
    try {
      const result = await api.get<VehicleVariant[]>(`/api/models/${opt.id}/variants`);
      // A newer selectModel() call may have started (and even finished)
      // while this one was in flight — never let a slower, stale response
      // overwrite the variants for whatever the user has since selected.
      if (variantsRequestGuard.current !== requestId) return;
      setVariants(result);
    } finally {
      if (variantsRequestGuard.current === requestId) setLoadingVariants(false);
    }
  }

  function selectYear(newYear: number | null) {
    setYear(newYear);
    setVariantId(null);
  }

  const years = Array.from(
    new Set(variants.map((v) => v.yearStart).filter((y): y is number => y !== null))
  ).sort((a, b) => b - a);

  const variantsForYear = year === null ? [] : variants.filter((v) => v.yearStart === year);

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <Combobox
          label="Manufacturer"
          placeholder="Select manufacturer"
          value={manufacturer}
          fetchOptions={fetchManufacturers}
          onSelect={selectManufacturer}
        />
        <Combobox
          label="Model"
          placeholder={manufacturer ? "Select model" : "Choose a manufacturer first"}
          value={model}
          disabled={!manufacturer}
          emptyMessage="No models found for this manufacturer in our catalogue"
          fetchOptions={fetchModels}
          onSelect={selectModel}
        />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <label htmlFor="vehicle-year" className="block text-xs font-medium text-muted mb-1">
            Year
          </label>
          <select
            id="vehicle-year"
            disabled={!model || loadingVariants}
            value={year ?? ""}
            onChange={(e) => selectYear(e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-lg border border-border bg-panel px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">{loadingVariants ? "Loading…" : "Select year"}</option>
            {years.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="vehicle-variant" className="block text-xs font-medium text-muted mb-1">
            Motorization
          </label>
          <select
            id="vehicle-variant"
            disabled={year === null}
            value={variantId ?? ""}
            onChange={(e) => setVariantId(e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-lg border border-border bg-panel px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-50"
          >
            <option value="">Select version</option>
            {variantsForYear.map((v) => (
              <option key={v.id} value={v.id}>
                {v.variantName}
              </option>
            ))}
          </select>
        </div>
      </div>
      <button
        type="button"
        disabled={!variantId}
        onClick={() => variantId && onVariantSelected(variantId)}
        className="w-full rounded-lg bg-accent px-4 py-3 text-sm font-semibold text-white hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
      >
        Start a diagnosis →
      </button>
    </div>
  );
}
