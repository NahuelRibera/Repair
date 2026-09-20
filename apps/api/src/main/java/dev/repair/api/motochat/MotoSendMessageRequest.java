package dev.repair.api.motochat;

import jakarta.validation.constraints.NotBlank;

public record MotoSendMessageRequest(@NotBlank String content) {
}
