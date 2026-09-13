package dev.repair.api.chat;

import java.util.List;

public record ChatTurnResult(
        long messageId,
        DiagnosticAnswer answer,
        List<EvidenceCardDto> evidence,
        RagRunDebugDto debug
) {
}
