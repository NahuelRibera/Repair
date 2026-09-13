package dev.repair.api.conversation;

import jakarta.validation.constraints.NotNull;

public record CreateSessionRequest(@NotNull Long variantId) {
}
