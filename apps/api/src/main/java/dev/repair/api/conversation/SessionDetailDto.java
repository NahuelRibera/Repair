package dev.repair.api.conversation;

import java.util.List;

public record SessionDetailDto(SessionSummaryDto session, List<MessageDto> messages) {
}
