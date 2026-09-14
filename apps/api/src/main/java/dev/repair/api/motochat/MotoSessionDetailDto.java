package dev.repair.api.motochat;

import java.util.List;

public record MotoSessionDetailDto(MotoSessionSummaryDto session, List<MotoMessageDto> messages) {
}
