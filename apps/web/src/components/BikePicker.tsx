"use client";

import { useCallback, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { MotoManufacturer, MotoModel } from "@/lib/types";
import { Combobox, type ComboboxOption } from "./Combobox";

interface BikePickerProps {
  onSelected: (modelId: number, year: number) => void;
  submitLabel?: string;
}

/**
 * Manufacturer -> model -> year, entirely driven by the dynamic catalog
 * API (see docs/repair-v2-architecture.md section 3). Nothing here is a
 * hardcoded manufacturer/model/year — every option comes from whatever
 * has actually been ingested from knowledge/motorcycles/**\/*.md.
 */
export function BikePicker({ onSelected, submitLabel = "Choose this bike →" }: BikePickerProps) {
  const [manufacturer, setManufacturer] = useState<ComboboxOption | null>(null);
  const [model, setModel] = useState<ComboboxOption | null>(null);
  const [years, setYears] = useState<number[]>([]);
  const [year, setYear] = useState<number | null>(null);
  const [loadingYears, setLoadingYears] = useState(false);
  const yearsRequestGuard = useRef(0);

  const fetchManufacturers = useCallback(async (search: string): Promise<ComboboxOption[]> => {
    const all = await api.get<MotoManufacturer[]>("/api/motorcycles/manufacturers");
    const lower = search.trim().toLowerCase();
    return all
      .filter((m) => m.name.toLowerCase().includes(lower))
      .map((m) => ({ id: m.id, label: m.name }));
  }, []);

  const fetchModels = useCallback(
    async (search: string): Promise<ComboboxOption[]> => {
      if (!manufacturer) return [];
      const all = await api.get<MotoModel[]>(`/api/motorcycles/manufacturers/${manufacturer.id}/models`);
      const lower = search.trim().toLowerCase();
      return all
        .filter((m) => m.name.toLowerCase().includes(lower))
        .map((m) => ({ id: m.id, label: m.name }));
    },
    [manufacturer]
  );

  function selectManufacturer(opt: ComboboxOption) {
    setManufacturer(opt);
    setModel(null);
    setYears([]);
    setYear(null);
  }

  async function selectModel(opt: ComboboxOption) {
    setModel(opt);
    setYear(null);
    setYears([]);
    setLoadingYears(true);
    const requestId = ++yearsRequestGuard.current;
    try {
      const result = await api.get<number[]>(`/api/motorcycles/models/${opt.id}/years`);
      if (yearsRequestGuard.current !== requestId) return;
      setYears(result);
    } finally {
      if (yearsRequestGuard.current === requestId) setLoadingYears(false);
    }
  }

  return (
    <div className="space-y-3">
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
        emptyMessage="No models found for this manufacturer yet"
        fetchOptions={fetchModels}
        onSelect={selectModel}
      />
      <div>
        <label htmlFor="bike-year" className="block text-xs font-medium text-muted mb-1">
          Year
        </label>
        <select
          id="bike-year"
          disabled={!model || loadingYears}
          value={year ?? ""}
          onChange={(e) => setYear(e.target.value ? Number(e.target.value) : null)}
          className="w-full rounded-lg border border-border bg-panel px-3 py-2.5 text-sm disabled:cursor-not-allowed disabled:opacity-50"
        >
          <option value="">{loadingYears ? "Loading…" : "Select year"}</option>
          {years.map((y) => (
            <option key={y} value={y}>
              {y}
            </option>
          ))}
        </select>
      </div>
      <button
        type="button"
        disabled={!model || year === null}
        onClick={() => model && year !== null && onSelected(model.id, year)}
        className="w-full rounded-lg bg-accent px-4 py-3 text-sm font-semibold text-white hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40 transition-colors"
      >
        {submitLabel}
      </button>
    </div>
  );
}
