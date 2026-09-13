package dev.repair.api.chat;

import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import dev.repair.api.config.ChatProperties;
import dev.repair.api.conversation.SessionRepository;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final SessionRepository sessionRepository;
    private final ChatOrchestrationService orchestrationService;
    private final RagRunRepository ragRunRepository;
    private final ChatProperties chatProperties;
    private final VisitorContext visitor;

    public ChatController(
            SessionRepository sessionRepository, ChatOrchestrationService orchestrationService,
            RagRunRepository ragRunRepository, ChatProperties chatProperties, VisitorContext visitor
    ) {
        this.sessionRepository = sessionRepository;
        this.orchestrationService = orchestrationService;
        this.ragRunRepository = ragRunRepository;
        this.chatProperties = chatProperties;
        this.visitor = visitor;
    }

    @PostMapping("/api/sessions/{sessionId}/messages")
    public ChatTurnResult sendMessage(@PathVariable long sessionId, @Valid @RequestBody SendMessageRequest request) {
        Long variantId = sessionRepository.findVariantIdForSession(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
        if (request.content().length() > chatProperties.maxMessageLength()) {
            throw new BadRequestException("Message is too long (max " + chatProperties.maxMessageLength() + " characters)");
        }
        return orchestrationService.handleUserMessage(sessionId, variantId, request.content().trim());
    }

    @GetMapping("/api/rag-runs/{requestId}")
    public RagRunDebugAndEvidence debug(@PathVariable UUID requestId) {
        RagRunDebugDto debug = ragRunRepository.findDebugForVisitor(requestId, visitor.getVisitorId())
                .orElseThrow(() -> new NotFoundException("Request not found"));
        return new RagRunDebugAndEvidence(debug, ragRunRepository.findEvidenceForVisitor(requestId, visitor.getVisitorId()));
    }

    public record RagRunDebugAndEvidence(RagRunDebugDto debug, java.util.List<EvidenceCardDto> evidence) {
    }
}
