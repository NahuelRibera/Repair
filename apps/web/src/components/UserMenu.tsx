"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useAuth, loginUrl } from "@/lib/AuthProvider";
import { AccountAvatar } from "./AccountAvatar";

/** The subtle top-right account control — deliberately not a full
 * profile page (My Garage is the rider's real "account" area). Shows a
 * "Sign in" link when signed out, or a simple menu (My Garage / Sign
 * out) when signed in — see docs/authentication.md "Profile menu". */
export function UserMenu({ dark = false }: { dark?: boolean }) {
  const { user, loading, setUser } = useAuth();
  const pathname = usePathname();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: PointerEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  async function signOut() {
    setOpen(false);
    await api.post<void>("/api/auth/logout");
    setUser(null);
    router.push("/");
  }

  if (loading) {
    return <span className="h-8 w-8 rounded-full bg-black/5 animate-pulse" aria-hidden />;
  }

  if (!user) {
    return (
      <a
        href={loginUrl(pathname || "/chat")}
        className={`rounded-lg px-4 py-2 text-sm font-semibold transition-colors ${
          dark ? "bg-white text-navy hover:bg-white/90" : "bg-accent text-white hover:bg-accent-hover"
        }`}
      >
        Sign in
      </a>
    );
  }

  return (
    <div className="relative" ref={menuRef}>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
        className={`flex items-center gap-2 rounded-full p-0.5 transition-colors ${
          dark ? "hover:bg-white/10" : "hover:bg-black/5"
        }`}
      >
        <AccountAvatar displayName={user.displayName} email={user.email} pictureUrl={user.googlePictureUrl} size={32} />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 mt-2 w-52 rounded-xl border border-border bg-panel shadow-lg py-1.5 text-sm z-50"
        >
          <div className="px-3.5 py-2 border-b border-border mb-1">
            <p className="font-medium truncate">{user.displayName || user.email}</p>
            <p className="text-xs text-muted truncate">{user.email}</p>
          </div>
          <Link
            href="/garage"
            role="menuitem"
            onClick={() => setOpen(false)}
            className="block px-3.5 py-2 hover:bg-black/5"
          >
            My Garage
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={signOut}
            className="block w-full text-left px-3.5 py-2 hover:bg-black/5 text-red-600"
          >
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
