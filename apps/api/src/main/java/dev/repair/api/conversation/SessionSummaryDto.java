package dev.repair.api.conversation;

import java.time.OffsetDateTime;

public record SessionSummaryDto(
        long id,
        String title,
        long variantId,
        String manufacturerName,
        String modelName,
        String variantName,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
