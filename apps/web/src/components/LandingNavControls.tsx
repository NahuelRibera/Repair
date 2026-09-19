"use client";

import { UserMenu } from "./UserMenu";

/** The right side of the public landing nav — auth-aware: "Sign in" when
 * signed out, the account menu when signed in. There is deliberately no
 * separate "Open the app" button here; the hero and bike picker are
 * already the entry points into the product. */
export function LandingNavControls() {
  return (
    <div className="flex items-center gap-3">
      <UserMenu />
    </div>
  );
}
