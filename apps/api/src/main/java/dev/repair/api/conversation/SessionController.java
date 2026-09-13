package dev.repair.api.conversation;

import dev.repair.api.catalogue.CatalogueRepository;
import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    private final SessionRepository sessionRepository;
    private final CatalogueRepository catalogueRepository;
    private final VisitorContext visitor;

    public SessionController(
            SessionRepository sessionRepository, CatalogueRepository catalogueRepository, VisitorContext visitor
    ) {
        this.sessionRepository = sessionRepository;
        this.catalogueRepository = catalogueRepository;
        this.visitor = visitor;
    }

    @PostMapping("/api/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDetailDto createSession(@Valid @RequestBody CreateSessionRequest request) {
        var variant = catalogueRepository.findVariantDetail(request.variantId())
                .orElseThrow(() -> new BadRequestException("Unknown vehicle variant"));
        String title = variant.modelName().toLowerCase().startsWith(variant.manufacturerName().toLowerCase())
                ? variant.modelName()
                : variant.manufacturerName() + " " + variant.modelName();
        long sessionId = sessionRepository.createSession(visitor.getVisitorId(), request.variantId(), title);
        return sessionRepository.findSessionDetail(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new IllegalStateException("Session vanished immediately after creation"));
    }

    @GetMapping("/api/sessions")
    public List<SessionSummaryDto> listSessions(@RequestParam(required = false) String search) {
        return sessionRepository.listSessions(visitor.getVisitorId(), search);
    }

    @GetMapping("/api/sessions/{sessionId}")
    public SessionDetailDto sessionDetail(@PathVariable long sessionId) {
        return sessionRepository.findSessionDetail(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
    }

    @DeleteMapping("/api/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable long sessionId) {
        boolean deleted = sessionRepository.softDelete(visitor.getVisitorId(), sessionId);
        if (!deleted) {
            throw new NotFoundException("Conversation not found");
        }
    }
}
