package dev.repair.api.conversation;

import java.time.OffsetDateTime;

public record MessageDto(
        long id,
        String role,
        String content,
        String structuredResponseJson,
        OffsetDateTime createdAt
) {
}
