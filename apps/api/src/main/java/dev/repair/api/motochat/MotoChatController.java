package dev.repair.api.motochat;

import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import dev.repair.api.config.ChatProperties;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MotoChatController {

    private final MotoChatSessionRepository sessionRepository;
    private final MotoChatOrchestrationService orchestrationService;
    private final MotoRagRunRepository ragRunRepository;
    private final ChatProperties chatProperties;
    private final VisitorContext visitor;

    public MotoChatController(
            MotoChatSessionRepository sessionRepository, MotoChatOrchestrationService orchestrationService,
            MotoRagRunRepository ragRunRepository, ChatProperties chatProperties, VisitorContext visitor
    ) {
        this.sessionRepository = sessionRepository;
        this.orchestrationService = orchestrationService;
        this.ragRunRepository = ragRunRepository;
        this.chatProperties = chatProperties;
        this.visitor = visitor;
    }

    @PostMapping("/api/moto-sessions/{sessionId}/messages")
    public MotoChatTurnResult sendMessage(@PathVariable long sessionId, @Valid @RequestBody MotoSendMessageRequest request) {
        Long garageVehicleId = sessionRepository.findGarageVehicleIdForSession(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
        if (request.content().length() > chatProperties.maxMessageLength()) {
            throw new BadRequestException("Message is too long (max " + chatProperties.maxMessageLength() + " characters)");
        }
        return orchestrationService.handleUserMessage(visitor.getVisitorId(), sessionId, garageVehicleId, request.content().trim());
    }

    @GetMapping("/api/moto-rag-runs/{requestId}")
    public MotoRagRunDebugAndEvidence debug(@PathVariable UUID requestId) {
        MotoRagRunDebugDto debug = ragRunRepository.findDebugForVisitor(requestId, visitor.getVisitorId())
                .orElseThrow(() -> new NotFoundException("Request not found"));
        return new MotoRagRunDebugAndEvidence(debug, ragRunRepository.findEvidenceForVisitor(requestId, visitor.getVisitorId()));
    }

    public record MotoRagRunDebugAndEvidence(MotoRagRunDebugDto debug, List<MotoEvidenceCardDto> evidence) {
    }
}
