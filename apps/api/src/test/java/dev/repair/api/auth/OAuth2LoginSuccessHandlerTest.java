package dev.repair.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Regression coverage for a real, reproduced bug: a successful Google
 * login left the browser on this backend's own Whitelabel 404 page
 * instead of the frontend, because Google's OAuth callback is the one
 * request in the whole flow the browser sends directly to this backend's
 * own origin (bypassing the Next.js dev proxy), and a bare relative
 * {@code sendRedirect("/chat")} resolves against THAT origin. See
 * OAuth2LoginSuccessHandler's own doc comment and docs/authentication.md.
 */
@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    private static final String FRONTEND_BASE_URL = "http://localhost:3000";

    @Mock
    private AppUserService appUserService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private OAuth2LoginSuccessHandler handler;

    private OidcUser sampleOidcUser() {
        OidcIdToken idToken = new OidcIdToken(
                "id-token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of(
                        IdTokenClaimNames.SUB, "google-sub-123",
                        StandardClaimNames.EMAIL, "rider@example.test",
                        StandardClaimNames.NAME, "Rider Example",
                        StandardClaimNames.PICTURE, "https://example.test/pic.jpg"
                )
        );
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, IdTokenClaimNames.SUB);
    }

    private OAuth2AuthenticationToken sampleAuthentication() {
        OidcUser oidcUser = sampleOidcUser();
        return new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "google");
    }

    @Test
    void redirectsToAnAbsoluteFrontendUrlNotARelativePath() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(location.capture());
        assertThat(location.getValue()).isEqualTo("http://localhost:3000/chat");
    }

    @Test
    void redirectsToTheValidatedPendingDestinationUnderTheFrontendOrigin() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "%2Fgarage")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        verify(response).sendRedirect("http://localhost:3000/garage");
    }

    @Test
    void anUnsafePendingDestinationFallsBackToDefaultButStillUnderTheFrontendOrigin() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "https%3A%2F%2Fevil.example.com")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        ArgumentCaptor<String> location = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(location.capture());
        assertThat(location.getValue()).isEqualTo("http://localhost:3000/chat");
        assertThat(location.getValue()).doesNotContain("evil.example.com");
    }

    @Test
    void aProtocolRelativePendingDestinationFallsBackToDefault() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "%2F%2Fevil.example.com")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        verify(response).sendRedirect("http://localhost:3000/chat");
    }

    @Test
    void explicitlyRequestedChatDestinationRedirectsUnderTheFrontendOrigin() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "%2Fchat")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        verify(response).sendRedirect("http://localhost:3000/chat");
    }

    @Test
    void aMalformedDestinationWithEmbeddedControlCharactersFallsBackToDefault() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "%2Fchat%0D%0ASet-Cookie%3A+evil%3D1")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        verify(response).sendRedirect("http://localhost:3000/chat");
    }

    @Test
    void findsOrCreatesTheAppUserFromTheOidcClaims() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(null);

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        verify(appUserService).findOrCreate(
                eq("google-sub-123"), eq("rider@example.test"), eq("Rider Example"), eq("https://example.test/pic.jpg")
        );
    }

    @Test
    void clearsThePendingRedirectCookieRegardlessOfWhereItRedirects() throws Exception {
        handler = new OAuth2LoginSuccessHandler(appUserService, FRONTEND_BASE_URL);
        when(request.getCookies()).thenReturn(new Cookie[] {
                new Cookie("repair_post_login_redirect", "%2Fgarage")
        });

        handler.onAuthenticationSuccess(request, response, sampleAuthentication());

        ArgumentCaptor<Cookie> cookieCaptor = ArgumentCaptor.forClass(Cookie.class);
        verify(response).addCookie(cookieCaptor.capture());
        Cookie cleared = cookieCaptor.getValue();
        assertThat(cleared.getName()).isEqualTo("repair_post_login_redirect");
        assertThat(cleared.getMaxAge()).isEqualTo(0);
    }
}
