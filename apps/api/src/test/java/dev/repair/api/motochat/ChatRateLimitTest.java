package dev.repair.api.motochat;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.auth.AuthenticatedUserContext;
import dev.repair.api.common.ApiExceptionHandler;
import dev.repair.api.config.ChatProperties;
import dev.repair.api.config.ChatRateLimitProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ChatRateLimitTest {

    private static final int LIMIT = 3;
    private static final int WINDOW_SECONDS = 60;

    private final AtomicLong millis = new AtomicLong(1_000_000);
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    };

    private AuthenticatedUserContext user;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MotoChatSessionRepository sessions = mock(MotoChatSessionRepository.class);
        when(sessions.findGarageVehicleIdForSession(anyLong(), anyLong())).thenReturn(Optional.of(7L));
        user = mock(AuthenticatedUserContext.class);
        when(user.getUserId()).thenReturn(1L);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(LIMIT, WINDOW_SECONDS), clock);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MotoChatController(sessions, mock(MotoChatOrchestrationService.class),
                        mock(MotoRagRunRepository.class), new ChatProperties(10, 2000, 5, 500, 100, 30, 30), user, limiter))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private ResultActions send() throws Exception {
        return mockMvc.perform(post("/api/moto-sessions/3/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"hello\"}"));
    }

    private ResultActions confirm() throws Exception {
        return mockMvc.perform(post("/api/moto-sessions/3/pending-actions/5/confirm"));
    }

    @Test
    void requestsBelowTheLimitAreUnaffected() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            send().andExpect(status().isOk());
        }
    }

    @Test
    void requestsAboveTheLimitGet429WithRetryAfter() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            send().andExpect(status().isOk());
        }
        millis.addAndGet(10_000);
        send().andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "50"))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void confirmationsShareTheSameAllowance() throws Exception {
        send().andExpect(status().isOk());
        send().andExpect(status().isOk());
        confirm().andExpect(status().isOk());
        confirm().andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        send().andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherUserIsNotAffected() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            send().andExpect(status().isOk());
        }
        send().andExpect(status().isTooManyRequests());
        when(user.getUserId()).thenReturn(2L);
        send().andExpect(status().isOk());
    }

    @Test
    void requestsResumeAfterTheWindow() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            send().andExpect(status().isOk());
        }
        send().andExpect(status().isTooManyRequests());
        millis.addAndGet(Duration.ofSeconds(WINDOW_SECONDS).toMillis());
        send().andExpect(status().isOk());
    }
}
