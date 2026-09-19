"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, ApiError } from "./api";
import type { AppUser } from "./types";

interface AuthState {
  user: AppUser | null;
  loading: boolean;
  refresh: () => Promise<void>;
  setUser: (user: AppUser | null) => void;
}

const AuthContext = createContext<AuthState | null>(null);

/** Wraps the whole app (see app/layout.tsx) so every surface — the public
 * landing page's auth-aware nav, and every gated page's RequireAuth check
 * — shares one GET /api/me call instead of each re-fetching it. A 401
 * here is an entirely normal, expected state (not signed in yet), never
 * logged as an error. */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AppUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const me = await api.get<AppUser>("/api/me");
      setUser(me);
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 401)) {
        console.error("Failed to load the current user", err);
      }
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return <AuthContext.Provider value={{ user, loading, refresh, setUser }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within an AuthProvider");
  return ctx;
}

/** Full-page navigation (never a fetch) — this hands off to Spring
 * Security's own OAuth2 authorization endpoint and eventually to Google,
 * so it must be a real browser navigation. `next` is re-validated
 * server-side (RedirectValidator) regardless of what's passed here. */
export function loginUrl(next: string): string {
  return `/api/auth/login?next=${encodeURIComponent(next)}`;
}
