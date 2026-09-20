package dev.repair.api.auth;

import java.time.OffsetDateTime;

public record AppUserDto(
        long id, String googleSub, String email, String displayName,
        String googlePictureUrl, OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
}
