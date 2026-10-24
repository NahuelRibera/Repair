package dev.repair.api.motochat;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.auth.AuthenticatedUserContext;
import dev.repair.api.common.ApiExceptionHandler;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.config.ChatProperties;
import dev.repair.api.config.ChatRateLimitProperties;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The HTTP contract the web chat relies on to tell an expired proposal
 * apart from one that is missing or already resolved. */
class MotoPendingActionEndpointTest {

    private MotoChatOrchestrationService orchestration;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MotoChatSessionRepository sessions = mock(MotoChatSessionRepository.class);
        when(sessions.findGarageVehicleIdForSession(anyLong(), anyLong())).thenReturn(Optional.of(7L));
        orchestration = mock(MotoChatOrchestrationService.class);
        AuthenticatedUserContext user = mock(AuthenticatedUserContext.class);
        when(user.getUserId()).thenReturn(1L);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MotoChatController(sessions, orchestration,
                        mock(MotoRagRunRepository.class), new ChatProperties(10, 2000, 5, 500, 100, 30, 30), user,
                        new ChatRateLimiter(new ChatRateLimitProperties(100, 60), Clock.systemUTC())))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void confirmingAnExpiredProposalReturnsGoneWithTheExpiredCode() throws Exception {
        when(orchestration.confirmPendingAction(1L, 3L, 9L)).thenThrow(new PendingActionExpiredException("expired"));

        mockMvc.perform(post("/api/moto-sessions/3/pending-actions/9/confirm"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(PendingActionExpiredException.CODE))
                .andExpect(jsonPath("$.error").value("expired"));
    }

    @Test
    void confirmingAMissingProposalStaysNotFoundWithoutTheExpiredCode() throws Exception {
        when(orchestration.confirmPendingAction(1L, 3L, 9L)).thenThrow(new NotFoundException("gone"));

        mockMvc.perform(post("/api/moto-sessions/3/pending-actions/9/confirm"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
