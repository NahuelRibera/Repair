package dev.repair.api.motochat;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.repair.api.auth.AuthenticatedUserContext;
import dev.repair.api.common.ApiExceptionHandler;
import dev.repair.api.config.ChatProperties;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class MotoChatMessageLengthTest {

    private static final int MAX = 2000;

    private MotoChatOrchestrationService orchestration;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MotoChatSessionRepository sessions = mock(MotoChatSessionRepository.class);
        when(sessions.findGarageVehicleIdForSession(anyLong(), anyLong())).thenReturn(Optional.of(7L));
        orchestration = mock(MotoChatOrchestrationService.class);
        AuthenticatedUserContext user = mock(AuthenticatedUserContext.class);
        when(user.getUserId()).thenReturn(1L);
        ChatProperties properties = new ChatProperties(10, MAX, 5, 500, 100, 30);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new MotoChatController(sessions, orchestration, mock(MotoRagRunRepository.class), properties, user,
                        new ChatRateLimiter(new dev.repair.api.config.ChatRateLimitProperties(100, 60))))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private org.springframework.test.web.servlet.ResultActions send(String content) throws Exception {
        return mockMvc.perform(post("/api/moto-sessions/3/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + content + "\"}"));
    }

    @Test
    void messageAtTheLimitIsProcessed() throws Exception {
        String content = "a".repeat(MAX);
        send(content).andExpect(status().isOk());
        verify(orchestration).handleUserMessage(eq(1L), eq(3L), eq(7L), eq(content));
    }

    @Test
    void messageOneCharacterOverTheLimitIsRejectedWithTheMaximumInTheError() throws Exception {
        send("a".repeat(MAX + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("max " + MAX + " characters")));
        verify(orchestration, never()).handleUserMessage(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        send("   ").andExpect(status().isBadRequest());
        verify(orchestration, never()).handleUserMessage(anyLong(), anyLong(), anyLong(), any());
    }
}
