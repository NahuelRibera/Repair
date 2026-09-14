"use client";

import Link from "next/link";
import { usePathname, useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { bikeTitle } from "@/lib/types";
import type { MotoSessionSummary } from "@/lib/types";

export function Sidebar({ mobileOpen, onClose }: { mobileOpen: boolean; onClose: () => void }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useParams<{ sessionId?: string }>();
  const activeId = params?.sessionId ? Number(params.sessionId) : null;

  const [sessions, setSessions] = useState<MotoSessionSummary[]>([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null);

  async function refresh() {
    setLoading(true);
    try {
      const results = await api.get<MotoSessionSummary[]>("/api/moto-sessions");
      setSessions(results);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    refresh();
  }, [activeId]);

  const filtered = search.trim()
    ? sessions.filter((s) => `${s.title ?? ""} ${bikeTitle(s)}`.toLowerCase().includes(search.trim().toLowerCase()))
    : sessions;

  async function confirmDelete(id: number) {
    await api.del(`/api/moto-sessions/${id}`);
    setPendingDeleteId(null);
    if (activeId === id) {
      router.push("/chat");
    }
    refresh();
  }

  return (
    <>
      {mobileOpen && (
        <div className="fixed inset-0 bg-black/50 z-30 lg:hidden" onClick={onClose} />
      )}
      <aside
        className={`fixed lg:static inset-y-0 left-0 z-40 w-72 h-dvh lg:h-auto bg-navy flex flex-col shrink-0 transform transition-transform lg:transform-none ${
          mobileOpen ? "translate-x-0" : "-translate-x-full lg:translate-x-0"
        }`}
      >
        <div className="p-4 border-b border-white/10 shrink-0">
          <Link href="/" className="flex items-center gap-2.5 mb-4">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-white text-navy shrink-0">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                <path
                  d="M21.7 16.3l-4-4a5 5 0 0 0-6.2-6.2L8.4 9.2 4.9 5.7 2.3 8.3l3.5 3.5-3.1 3.1a5 5 0 0 0 6.2 6.2l4-4 4 4 4.8-4.8zM8 20a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"
                  fill="currentColor"
                />
              </svg>
            </span>
            <span className="text-white font-semibold">Repair</span>
          </Link>
          <Link
            href="/chat"
            className="flex items-center justify-center gap-2 w-full rounded-lg bg-accent px-3 py-2.5 text-sm font-semibold text-white hover:bg-accent-hover transition-colors mb-2"
          >
            + New chat
          </Link>
          <Link
            href="/garage"
            className={`flex items-center justify-center gap-2 w-full rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors ${
              pathname?.startsWith("/garage") ? "bg-white/15 text-white" : "bg-white/5 text-white/80 hover:bg-white/10"
            }`}
          >
            My Garage
          </Link>
        </div>

        <div className="p-3 border-b border-white/10 shrink-0">
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search conversations…"
            className="w-full rounded-lg bg-white/5 border border-white/10 px-3 py-2 text-sm text-white placeholder:text-white/40 outline-none focus:border-accent/50"
          />
        </div>

        <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain p-2">
          {loading && sessions.length === 0 && (
            <p className="px-3 py-2 text-sm text-white/40">Loading…</p>
          )}
          {!loading && filtered.length === 0 && (
            <p className="px-3 py-2 text-sm text-white/40">No conversations yet.</p>
          )}
          {filtered.map((s) => (
            <div key={s.id} className="group relative">
              <Link
                href={`/chat/${s.id}`}
                onClick={onClose}
                className={`block rounded-lg px-3 py-2.5 mb-1 pr-9 ${
                  activeId === s.id ? "bg-white/10" : "hover:bg-white/5"
                }`}
              >
                <p className="text-sm text-white truncate">{s.title ?? bikeTitle(s)}</p>
                <p className="text-xs text-white/40 truncate">{s.year}</p>
              </Link>
              <button
                type="button"
                aria-label="Delete conversation"
                onClick={() => setPendingDeleteId(s.id)}
                className="absolute right-2 top-2.5 opacity-0 group-hover:opacity-100 text-white/40 hover:text-red-400 p-1"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                  <path
                    d="M6 6l12 12M18 6L6 18"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                </svg>
              </button>
            </div>
          ))}
        </div>
      </aside>

      {pendingDeleteId !== null && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-sm rounded-xl bg-panel border border-border p-5">
            <p className="font-semibold mb-1.5">Delete this conversation?</p>
            <p className="text-sm text-muted mb-5">This can&apos;t be undone.</p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setPendingDeleteId(null)}
                className="rounded-lg px-3.5 py-2 text-sm font-medium hover:bg-black/5"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => confirmDelete(pendingDeleteId)}
                className="rounded-lg bg-red-600 px-3.5 py-2 text-sm font-semibold text-white hover:bg-red-700"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
