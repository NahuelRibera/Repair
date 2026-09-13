package dev.repair.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repair.chat")
public record ChatProperties(
        int maxHistoryMessages,
        int maxMessageLength,
        int maxRetrievedChunks,
        int maxOutputTokens,
        int maxEvidenceWords
) {
}
