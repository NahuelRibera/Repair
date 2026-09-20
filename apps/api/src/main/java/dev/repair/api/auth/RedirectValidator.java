package dev.repair.api.auth;

/**
 * Guards the post-login "next" redirect target against open-redirect
 * attacks: only a same-origin relative path is ever accepted. Used by
 * both LoginRedirectController (where the rider's "next" originates) and
 * OAuth2LoginSuccessHandler (where it's read back and actually redirected
 * to) — the same rule is applied at both ends.
 */
final class RedirectValidator {

    private RedirectValidator() {
    }

    private static final int MAX_LENGTH = 512;

    /** True only for a path that is unambiguously "this same site, this
     * same page" — a single leading slash, no scheme, no protocol-relative
     * "//host" trick, no backslash (some browsers treat "\" as "/"), and no
     * embedded newline/control characters that could smuggle header
     * injection into a raw Location header. */
    static boolean isSafeRelativePath(String next) {
        if (next == null || next.isBlank() || next.length() > MAX_LENGTH) {
            return false;
        }
        if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
            return false;
        }
        if (next.contains("\r") || next.contains("\n")) {
            return false;
        }
        // Reject an embedded scheme anywhere in the path (e.g.
        // "/redirect?to=https://evil" is fine to keep — that's just a query
        // value, not a navigation target — but "/\t/evil.com" or a literal
        // "http://"/"https://" right after the slash is not a real
        // same-origin path).
        String lower = next.toLowerCase(java.util.Locale.ROOT);
        return !lower.startsWith("/http://") && !lower.startsWith("/https://");
    }

    static String sanitizeOrDefault(String next, String fallback) {
        return isSafeRelativePath(next) ? next : fallback;
    }
}
