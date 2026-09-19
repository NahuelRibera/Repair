package dev.repair.api.config;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the actual security posture end to end (real Spring Security
 * filter chain, not a mocked one): the motorcycle domain is authenticated-
 * only, the dynamic catalog and car prototype stay public, an
 * unauthenticated protected request gets a plain 401 JSON body (never a
 * login-page redirect — see ApiAuthenticationEntryPoint), and the CSRF
 * cookie a mutating request needs is always issued. See
 * docs/authentication.md "Security review".
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SecurityConfigIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedGarageEndpointRejectsAnUnauthenticatedRequestWithPlainJson401() throws Exception {
        mockMvc.perform(get("/api/garage/vehicles"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"error\":\"unauthenticated\"}"));
    }

    @Test
    void protectedMeEndpointRejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedMotoSessionsEndpointRejectsAnUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/moto-sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    void dynamicMotorcycleCatalogStaysPublic() throws Exception {
        mockMvc.perform(get("/api/motorcycles/manufacturers")).andExpect(status().isOk());
    }

    @Test
    void everyResponseCarriesAReadableCsrfCookieForTheSpaToEchoBackOnMutations() throws Exception {
        mockMvc.perform(get("/api/motorcycles/manufacturers"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }

    @Test
    void aMutatingRequestWithoutTheCsrfHeaderIsRejected() throws Exception {
        mockMvc.perform(post("/api/moto-sessions").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthEndpointStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void loginEndpointRedirectsTowardTheOAuth2AuthorizationEndpoint() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("/api/oauth2/authorization/google")));
    }
}
