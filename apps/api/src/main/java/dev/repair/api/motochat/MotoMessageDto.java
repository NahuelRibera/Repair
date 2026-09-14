package dev.repair.api.motochat;

import java.time.OffsetDateTime;

public record MotoMessageDto(long id, String role, String content, String structuredResponseJson, OffsetDateTime createdAt) {
}
