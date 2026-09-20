"use client";

import { useState } from "react";
import { Sidebar } from "./Sidebar";
import { UserMenu } from "./UserMenu";

export function ChatShell({ children }: { children: React.ReactNode }) {
  const [mobileOpen, setMobileOpen] = useState(false);

  return (
    // h-dvh (not h-screen/100vh) pins this to the real visible viewport,
    // including on mobile where the address bar changes the visible area —
    // and, critically, it's an absolute size independent of the parent
    // <body>'s height, which is intentionally `min-h-full` (unbounded) so
    // the marketing landing page keeps scrolling normally outside /chat.
    // overflow-hidden here means the browser never shows an outer page
    // scrollbar for this route; the sidebar list and the message area each
    // get their own independent overflow-y-auto region below.
    <div className="h-dvh flex overflow-hidden">
      <Sidebar mobileOpen={mobileOpen} onClose={() => setMobileOpen(false)} />
      <div className="flex-1 flex flex-col min-w-0">
        <div className="flex items-center gap-3 border-b border-border px-4 py-3 shrink-0">
          <button
            type="button"
            aria-label="Open menu"
            onClick={() => setMobileOpen(true)}
            className="p-1.5 -ml-1.5 lg:hidden"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path d="M3 6h18M3 12h18M3 18h18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </button>
          <span className="font-semibold text-sm lg:hidden">Repair</span>
          <div className="flex-1" />
          <UserMenu />
        </div>
        <div className="flex-1 min-h-0 flex flex-col">{children}</div>
      </div>
    </div>
  );
}
