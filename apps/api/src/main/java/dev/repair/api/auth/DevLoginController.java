package dev.repair.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual-QA and automated-test-only substitute for the real Google OAuth2
 * login dance, so this app's authenticated surfaces can be exercised
 * without a live Google account — see docs/authentication.md "Testing
 * without a Google account". This is NOT wired into any code path a real
 * deployment would reach:
 *
 * <ul>
 *   <li>Only registered as a bean when the "dev" Spring profile is
 *       active ({@code @Profile("dev")}) — never present at all unless
 *       something explicitly passes {@code --spring.profiles.active=dev}
 *       or {@code SPRING_PROFILES_ACTIVE=dev}. There is no property flag
 *       to accidentally leave on; the endpoint simply does not exist
 *       otherwise.</li>
 *   <li>Never enabled by default, by {@code application.properties}, or
 *       by anything in {@code docker-compose.yml}.</li>
 *   <li>Builds a real, correctly-shaped {@code OidcUser} and stores it in
 *       the actual Spring Security session the same way a real login
 *       would, then runs it through the exact same
 *       {@code AppUserService.findOrCreate} as {@code OAuth2LoginSuccessHandler}
 *       — so everything downstream (ownership, ApiAuthenticationEntryPoint,
 *       AuthenticatedUserInterceptor) behaves identically to a real login.
 *       It does not weaken or bypass any authorization check.</li>
 * </ul>
 */
@RestController
@Profile("dev")
public class DevLoginController {

    private static final Logger log = LoggerFactory.getLogger(DevLoginController.class);

    private final AppUserService appUserService;

    public DevLoginController(AppUserService appUserService) {
        this.appUserService = appUserService;
        log.warn(
                "DevLoginController is active (Spring profile 'dev') — POST /api/auth/dev-login can sign in as "
                        + "any subject with no Google account. Never enable the 'dev' profile in a real deployment."
        );
    }

    public record DevLoginRequest(@NotBlank String sub, @NotBlank String email, String displayName) {
    }

    @PostMapping("/api/auth/dev-login")
    public AppUserDto devLogin(@Valid @RequestBody DevLoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AppUserDto user = appUserService.findOrCreate(
                "dev:" + request.sub(), request.email(), request.displayName(), null
        );

        OidcIdToken idToken = new OidcIdToken(
                "dev-fixture-token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of(IdTokenClaimNames.SUB, "dev:" + request.sub(), IdTokenClaimNames.ISS, "dev-fixture")
        );
        OidcUser oidcUser = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken, IdTokenClaimNames.SUB);
        Authentication authentication = new OAuth2AuthenticationToken(oidcUser, oidcUser.getAuthorities(), "google");

        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        SecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.saveContext(context, httpRequest, httpResponse);

        return user;
    }
}
