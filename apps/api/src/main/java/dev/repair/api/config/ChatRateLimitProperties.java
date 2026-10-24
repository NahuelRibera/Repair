package dev.repair.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-user limit on chat message sends and pending-action confirmations. */
@ConfigurationProperties(prefix = "repair.chat-rate-limit")
public record ChatRateLimitProperties(int maxRequests, int windowSeconds) {

    public ChatRateLimitProperties {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("repair.chat-rate-limit.max-requests must be at least 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("repair.chat-rate-limit.window-seconds must be at least 1");
        }
    }
}
