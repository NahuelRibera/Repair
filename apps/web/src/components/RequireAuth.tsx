"use client";

import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useAuth, loginUrl } from "@/lib/AuthProvider";

/** Gates every meaningful app surface (My Garage, chat, garage vehicle
 * detail, maintenance, preferences, Ask Repair) behind a real Repair
 * user, while the landing page stays public — see
 * docs/authentication.md "auth-gated routes". A full browser navigation
 * (not a client-side route change) to /api/auth/login, carrying the
 * current path as `next`, so the rider lands back exactly where they
 * tried to go once signed in. */
export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const pathname = usePathname();

  useEffect(() => {
    if (!loading && !user) {
      window.location.href = loginUrl(pathname || "/chat");
    }
  }, [loading, user, pathname]);

  if (loading || !user) {
    return (
      <div className="flex-1 flex items-center justify-center h-dvh">
        <p className="text-sm text-muted">Loading…</p>
      </div>
    );
  }

  return <>{children}</>;
}
