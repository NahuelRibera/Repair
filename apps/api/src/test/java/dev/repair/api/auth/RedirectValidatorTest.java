package dev.repair.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one thing standing between the post-login "next" redirect and an
 * open-redirect vulnerability (see LoginRedirectController and
 * OAuth2LoginSuccessHandler, both of which apply this same check).
 */
class RedirectValidatorTest {

    @Test
    void acceptsAnOrdinarySameOriginPath() {
        assertThat(RedirectValidator.isSafeRelativePath("/chat")).isTrue();
        assertThat(RedirectValidator.isSafeRelativePath("/garage/42")).isTrue();
        assertThat(RedirectValidator.isSafeRelativePath("/chat?resume=pending")).isTrue();
    }

    @Test
    void rejectsAProtocolRelativeHostTrick() {
        assertThat(RedirectValidator.isSafeRelativePath("//evil.example.com")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("/\\evil.example.com")).isFalse();
    }

    @Test
    void rejectsAnAbsoluteUrlDisguisedAsAPath() {
        assertThat(RedirectValidator.isSafeRelativePath("/http://evil.example.com")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("/HTTPS://evil.example.com")).isFalse();
    }

    @Test
    void rejectsMissingBlankOrNonRelativeInput() {
        assertThat(RedirectValidator.isSafeRelativePath(null)).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("   ")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("chat")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("https://evil.example.com/chat")).isFalse();
    }

    @Test
    void rejectsEmbeddedControlCharactersThatCouldSmuggleHeaderInjection() {
        assertThat(RedirectValidator.isSafeRelativePath("/chat\r\nSet-Cookie: evil=1")).isFalse();
        assertThat(RedirectValidator.isSafeRelativePath("/chat\nLocation: https://evil.example.com")).isFalse();
    }

    @Test
    void rejectsAnExcessivelyLongPath() {
        String tooLong = "/" + "a".repeat(600);
        assertThat(RedirectValidator.isSafeRelativePath(tooLong)).isFalse();
    }

    @Test
    void sanitizeOrDefaultFallsBackOnlyWhenUnsafe() {
        assertThat(RedirectValidator.sanitizeOrDefault("/garage", "/chat")).isEqualTo("/garage");
        assertThat(RedirectValidator.sanitizeOrDefault("//evil.example.com", "/chat")).isEqualTo("/chat");
        assertThat(RedirectValidator.sanitizeOrDefault(null, "/chat")).isEqualTo("/chat");
    }
}
