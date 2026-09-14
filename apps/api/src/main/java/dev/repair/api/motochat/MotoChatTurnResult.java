package dev.repair.api.motochat;

import java.util.List;

public record MotoChatTurnResult(
        long messageId, MotoDiagnosticAnswer answer, List<MotoEvidenceCardDto> evidence,
        List<ActionTakenDto> actionsTaken, MotoRagRunDebugDto debug
) {
}
