package dev.repair.api.motochat;

public record MotoRagRunDebugDto(
        String requestId,
        long garageVehicleId,
        String manufacturerName,
        String modelName,
        int year,
        String normalizedQuery,
        String embeddingModel,
        String generationModel,
        Integer promptTokens,
        Integer completionTokens,
        Long retrievalMillis,
        Long generationMillis,
        String providerStatus,
        String errorDetail,
        String actionsTakenJson
) {
}
