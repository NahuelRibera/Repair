package dev.repair.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repair.openai")
public record OpenAiProperties(
        String apiKey,
        String generationModel,
        String embeddingModel,
        int embeddingDimensions,
        int requestTimeoutSeconds,
        int maxRetries
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
