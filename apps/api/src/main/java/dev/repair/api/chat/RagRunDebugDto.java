package dev.repair.api.chat;

public record RagRunDebugDto(
        String requestId,
        long variantId,
        String embeddingModel,
        String generationModel,
        Integer promptTokens,
        Integer completionTokens,
        Long retrievalMillis,
        Long generationMillis,
        String providerStatus,
        String errorDetail
) {
}
