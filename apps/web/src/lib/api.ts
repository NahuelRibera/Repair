export class ApiError extends Error {
  status: number;
  code?: string;
  suggestedOdometerKm?: number;
  constructor(status: number, message: string, code?: string, suggestedOdometerKm?: number) {
    super(message);
    this.status = status;
    this.code = code;
    this.suggestedOdometerKm = suggestedOdometerKm;
  }
}

// Spring Security's CookieCsrfTokenRepository (see SecurityConfig) writes a
// readable XSRF-TOKEN cookie on every response and expects it echoed back
// as an X-XSRF-TOKEN header on every mutating request — the standard
// double-submit pattern for a pure-API backend with no server-rendered
// page. GET/HEAD are exempt by Spring Security itself, so only attach it
// for methods that can actually mutate state.
function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (method !== "GET" && method !== "HEAD") {
    const csrfToken = readCookie("XSRF-TOKEN");
    if (csrfToken) headers["X-XSRF-TOKEN"] = csrfToken;
  }
  const res = await fetch(path, {
    ...init,
    credentials: "include",
    headers,
  });
  if (!res.ok) {
    let message = res.statusText;
    let code: string | undefined;
    let suggested: number | undefined;
    try {
      const body = await res.json();
      if (body?.error) message = body.error;
      if (typeof body?.code === "string") code = body.code;
      if (typeof body?.suggestedOdometerKm === "number") suggested = body.suggestedOdometerKm;
    } catch {
      // ignore body parse failure, fall back to statusText
    }
    throw new ApiError(res.status, message, code, suggested);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return res.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body ? JSON.stringify(body) : undefined }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "PATCH", body: body ? JSON.stringify(body) : undefined }),
  del: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};
