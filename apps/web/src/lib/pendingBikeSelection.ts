// Preserves the bike a visitor picked on the public landing page across
// the full Google OAuth browser round trip (LoginRedirectController's
// pending-redirect cookie only carries a path, not arbitrary selection
// data — see docs/authentication.md). localStorage survives top-level
// navigations to the same origin, which is all this needs: the rider
// never leaves this browser, just leaves the SPA's in-memory state.
const KEY = "repair.pendingBikeSelection";

export interface PendingBikeSelection {
  modelId: number;
  year: number;
}

export function savePendingBikeSelection(selection: PendingBikeSelection) {
  try {
    localStorage.setItem(KEY, JSON.stringify(selection));
  } catch {
    // Private browsing / storage disabled — the rider just re-picks the
    // bike after signing in instead. Not worth failing the sign-in over.
  }
}

export function takePendingBikeSelection(): PendingBikeSelection | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    localStorage.removeItem(KEY);
    const parsed = JSON.parse(raw);
    if (typeof parsed?.modelId === "number" && typeof parsed?.year === "number") {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}
