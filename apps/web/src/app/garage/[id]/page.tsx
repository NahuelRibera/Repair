"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { bikeTitle, SERVICE_TYPE_LABELS } from "@/lib/types";
import type {
  GarageVehicle,
  MaintenanceEvent,
  MaintenanceServiceType,
  MaintenanceStatus,
  MaintenanceStatusCard,
  MotoSessionDetail,
} from "@/lib/types";

const STATUS_STYLES: Record<MaintenanceStatus, { label: string; className: string }> = {
  OK: { label: "OK", className: "bg-green-50 text-green-700 border-green-200" },
  DUE_SOON: { label: "Due soon", className: "bg-amber-50 text-amber-800 border-amber-200" },
  DUE: { label: "Due", className: "bg-orange-50 text-orange-800 border-orange-200" },
  OVERDUE: { label: "Overdue", className: "bg-red-50 text-red-700 border-red-200" },
  INTERVAL_KNOWN_NO_HISTORY: { label: "Interval known", className: "bg-blue-50 text-blue-700 border-blue-200" },
  UNKNOWN: { label: "Unknown", className: "bg-gray-100 text-gray-600 border-gray-300" },
  DATA_INCONSISTENT: { label: "Check data", className: "bg-red-50 text-red-700 border-red-200" },
};

export default function GarageVehiclePage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const router = useRouter();

  const [vehicle, setVehicle] = useState<GarageVehicle | null>(null);
  const [dashboard, setDashboard] = useState<MaintenanceStatusCard[] | null>(null);
  const [history, setHistory] = useState<MaintenanceEvent[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [odometerDraft, setOdometerDraft] = useState("");
  const [savingOdometer, setSavingOdometer] = useState(false);
  const [showAddEvent, setShowAddEvent] = useState(false);
  const [startingChat, setStartingChat] = useState(false);

  function refresh() {
    Promise.all([
      api.get<GarageVehicle>(`/api/garage/vehicles/${id}`),
      api.get<MaintenanceStatusCard[]>(`/api/garage/vehicles/${id}/dashboard`),
      api.get<MaintenanceEvent[]>(`/api/garage/vehicles/${id}/maintenance`),
    ])
      .then(([v, d, h]) => {
        setVehicle(v);
        setDashboard(d);
        setHistory(h);
        setOdometerDraft(v.currentOdometerKm != null ? String(Math.round(v.currentOdometerKm)) : "");
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 404) {
          // The bike no longer exists (e.g. deleted from My Garage in
          // another tab, or this link is stale) — there's nothing useful
          // to show here, so send the rider back to the garage list
          // instead of leaving them on a dead/error page.
          router.replace("/garage");
          return;
        }
        setLoadError(e instanceof ApiError ? e.message : "Failed to load this bike");
      });
  }

  useEffect(refresh, [id]);

  async function saveOdometer() {
    const value = Number(odometerDraft);
    if (!odometerDraft || Number.isNaN(value) || value < 0) return;
    setSavingOdometer(true);
    try {
      await api.patch<GarageVehicle>(`/api/garage/vehicles/${id}`, { currentOdometerKm: value });
      refresh();
    } finally {
      setSavingOdometer(false);
    }
  }

  async function startChat() {
    setStartingChat(true);
    try {
      const detail = await api.post<MotoSessionDetail>("/api/moto-sessions", { garageVehicleId: id });
      router.push(`/chat/${detail.session.id}`);
    } finally {
      setStartingChat(false);
    }
  }

  if (loadError) {
    return (
      <div className="flex-1 flex items-center justify-center p-6 text-center">
        <p className="text-sm text-muted">{loadError}</p>
      </div>
    );
  }

  if (!vehicle || !dashboard || !history) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <p className="text-sm text-muted">Loading…</p>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain">
      <div className="mx-auto max-w-3xl px-4 sm:px-6 py-10">
        <div className="flex items-start justify-between gap-4 mb-6">
          <div>
            <h1 className="text-2xl font-bold">{vehicle.nickname || bikeTitle(vehicle)}</h1>
            <p className="text-muted">
              {bikeTitle(vehicle)} · {vehicle.year}
            </p>
          </div>
          <button
            type="button"
            onClick={startChat}
            disabled={startingChat}
            className="shrink-0 rounded-lg bg-accent px-4 py-2.5 text-sm font-semibold text-white hover:bg-accent-hover transition-colors disabled:opacity-50"
          >
            {startingChat ? "Starting…" : "Ask Repair →"}
          </button>
        </div>

        <div className="rounded-xl border border-border bg-panel p-5 mb-6">
          <label className="block text-xs font-medium text-muted mb-1.5">Current odometer (km)</label>
          <div className="flex gap-2">
            <input
              type="number"
              min={0}
              value={odometerDraft}
              onChange={(e) => setOdometerDraft(e.target.value)}
              placeholder="Not recorded"
              className="flex-1 rounded-lg border border-border bg-panel px-3 py-2 text-sm"
            />
            <button
              type="button"
              onClick={saveOdometer}
              disabled={savingOdometer}
              className="rounded-lg border border-border px-4 py-2 text-sm font-medium hover:bg-black/5 disabled:opacity-50"
            >
              Save
            </button>
          </div>
        </div>

        <h2 className="text-sm font-semibold text-muted uppercase tracking-wide mb-3">Maintenance status</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 mb-8">
          {dashboard.map((card) => (
            <StatusCardView key={card.serviceType} card={card} />
          ))}
        </div>

        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-muted uppercase tracking-wide">Maintenance history</h2>
          <button
            type="button"
            onClick={() => setShowAddEvent((v) => !v)}
            className="text-sm font-medium text-accent hover:underline"
          >
            {showAddEvent ? "Cancel" : "+ Record maintenance"}
          </button>
        </div>

        {showAddEvent && (
          <AddMaintenanceForm
            garageVehicleId={id}
            onSaved={() => {
              setShowAddEvent(false);
              refresh();
            }}
          />
        )}

        {history.length === 0 ? (
          <p className="text-sm text-muted">No maintenance recorded yet.</p>
        ) : (
          <ul className="space-y-2">
            {history.map((event) => (
              <li key={event.id} className="rounded-lg border border-border bg-panel px-4 py-3 flex items-center justify-between gap-3">
                <div>
                  <p className="text-sm font-medium">{SERVICE_TYPE_LABELS[event.serviceType]}</p>
                  <p className="text-xs text-muted">
                    {event.odometerKm != null ? `${Math.round(event.odometerKm).toLocaleString()} km` : ""}
                    {event.odometerKm != null && event.performedAt ? " · " : ""}
                    {event.performedAt ?? ""}
                    {event.notes ? ` · ${event.notes}` : ""}
                  </p>
                </div>
                <span className="text-[10px] rounded bg-black/5 px-1.5 py-0.5 text-muted shrink-0">
                  {event.createdVia === "chat" ? "via chat" : "manual"}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function StatusCardView({ card }: { card: MaintenanceStatusCard }) {
  const style = STATUS_STYLES[card.status];
  return (
    <div className="rounded-xl border border-border bg-panel p-4">
      <div className="flex items-start justify-between gap-2 mb-2">
        <p className="text-sm font-semibold">{SERVICE_TYPE_LABELS[card.serviceType]}</p>
        <span className={`shrink-0 rounded-full border px-2 py-0.5 text-[11px] font-medium ${style.className}`}>
          {style.label}
        </span>
      </div>
      {card.status === "UNKNOWN" ? (
        <div className="text-xs text-muted space-y-0.5">
          <p>{card.note ?? "Not enough data yet"}</p>
          {/* UNKNOWN means "no verified interval for this service" — it does NOT mean
              "no history". A completed service (e.g. a tire replacement, which has no
              fixed interval) is still real maintenance history and must stay visible
              here; only the interval/due-date calculation is unavailable. */}
          {card.lastOdometerKm != null && <p>Last: {Math.round(card.lastOdometerKm).toLocaleString()} km</p>}
          {card.lastPerformedAt && <p>On: {card.lastPerformedAt}</p>}
        </div>
      ) : card.status === "DATA_INCONSISTENT" ? (
        <p className="text-xs text-red-700">{card.note}</p>
      ) : card.status === "INTERVAL_KNOWN_NO_HISTORY" ? (
        <div className="text-xs text-muted space-y-0.5">
          {card.intervalKm != null && <p>Interval: every {Math.round(card.intervalKm).toLocaleString()} km</p>}
          {card.intervalMonths != null && <p>Interval: every {Math.round(card.intervalMonths)} months</p>}
          {card.remainingKm != null && (
            <p>Next scheduled: ~{Math.round(card.intervalKm ?? 0).toLocaleString()} km · {Math.round(card.remainingKm).toLocaleString()} km remaining</p>
          )}
          <p className="italic">{card.note ?? "No previous service recorded"}</p>
        </div>
      ) : (
        <div className="text-xs text-muted space-y-0.5">
          {card.lastOdometerKm != null && <p>Last: {Math.round(card.lastOdometerKm).toLocaleString()} km</p>}
          {card.lastPerformedAt && <p>On: {card.lastPerformedAt}</p>}
          {card.remainingKm != null && (
            <p>{card.remainingKm >= 0 ? `${Math.round(card.remainingKm).toLocaleString()} km remaining` : `${Math.round(-card.remainingKm).toLocaleString()} km overdue`}</p>
          )}
        </div>
      )}
    </div>
  );
}

const SERVICE_TYPES = Object.keys(SERVICE_TYPE_LABELS) as MaintenanceServiceType[];

function AddMaintenanceForm({ garageVehicleId, onSaved }: { garageVehicleId: number; onSaved: () => void }) {
  const [serviceType, setServiceType] = useState<MaintenanceServiceType>("ENGINE_OIL_CHANGE");
  const [odometerKm, setOdometerKm] = useState("");
  const [performedAt, setPerformedAt] = useState("");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!odometerKm && !performedAt) {
      setError("Enter an odometer reading, a date, or both.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await api.post(`/api/garage/vehicles/${garageVehicleId}/maintenance`, {
        serviceType,
        odometerKm: odometerKm ? Number(odometerKm) : null,
        performedAt: performedAt || null,
        notes: notes || null,
      });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Couldn't save this maintenance event.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="rounded-xl border border-border bg-panel p-4 mb-4 space-y-3">
      {error && <p className="text-sm text-red-700">{error}</p>}
      <div>
        <label className="block text-xs font-medium text-muted mb-1">Service type</label>
        <select
          value={serviceType}
          onChange={(e) => setServiceType(e.target.value as MaintenanceServiceType)}
          className="w-full rounded-lg border border-border bg-panel px-3 py-2 text-sm"
        >
          {SERVICE_TYPES.map((type) => (
            <option key={type} value={type}>
              {SERVICE_TYPE_LABELS[type]}
            </option>
          ))}
        </select>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="block text-xs font-medium text-muted mb-1">Odometer (km)</label>
          <input
            type="number"
            min={0}
            value={odometerKm}
            onChange={(e) => setOdometerKm(e.target.value)}
            className="w-full rounded-lg border border-border bg-panel px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-muted mb-1">Date</label>
          <input
            type="date"
            value={performedAt}
            onChange={(e) => setPerformedAt(e.target.value)}
            className="w-full rounded-lg border border-border bg-panel px-3 py-2 text-sm"
          />
        </div>
      </div>
      <div>
        <label className="block text-xs font-medium text-muted mb-1">Notes (optional)</label>
        <input
          type="text"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          className="w-full rounded-lg border border-border bg-panel px-3 py-2 text-sm"
        />
      </div>
      <button
        type="button"
        onClick={submit}
        disabled={saving}
        className="rounded-lg bg-accent px-4 py-2 text-sm font-semibold text-white hover:bg-accent-hover disabled:opacity-50"
      >
        {saving ? "Saving…" : "Save"}
      </button>
    </div>
  );
}
