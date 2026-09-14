package dev.repair.api.motochat;

import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import dev.repair.api.garage.GarageVehicleRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MotoSessionController {

    private final MotoChatSessionRepository sessionRepository;
    private final GarageVehicleRepository garageVehicleRepository;
    private final VisitorContext visitor;

    public MotoSessionController(
            MotoChatSessionRepository sessionRepository, GarageVehicleRepository garageVehicleRepository, VisitorContext visitor
    ) {
        this.sessionRepository = sessionRepository;
        this.garageVehicleRepository = garageVehicleRepository;
        this.visitor = visitor;
    }

    @PostMapping("/api/moto-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public MotoSessionDetailDto createSession(@Valid @RequestBody CreateMotoSessionRequest request) {
        var vehicle = garageVehicleRepository.find(visitor.getVisitorId(), request.garageVehicleId())
                .orElseThrow(() -> new BadRequestException("Unknown garage vehicle"));
        String title = vehicle.nickname() != null && !vehicle.nickname().isBlank()
                ? vehicle.nickname()
                : vehicle.manufacturerName() + " " + vehicle.modelName();
        long sessionId = sessionRepository.createSession(visitor.getVisitorId(), request.garageVehicleId(), title);
        return sessionRepository.findSessionDetail(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new IllegalStateException("Session vanished immediately after creation"));
    }

    @GetMapping("/api/moto-sessions")
    public List<MotoSessionSummaryDto> listSessions() {
        return sessionRepository.listSessions(visitor.getVisitorId());
    }

    @GetMapping("/api/moto-sessions/{sessionId}")
    public MotoSessionDetailDto sessionDetail(@PathVariable long sessionId) {
        return sessionRepository.findSessionDetail(visitor.getVisitorId(), sessionId)
                .orElseThrow(() -> new NotFoundException("Conversation not found"));
    }

    @DeleteMapping("/api/moto-sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable long sessionId) {
        if (!sessionRepository.softDelete(visitor.getVisitorId(), sessionId)) {
            throw new NotFoundException("Conversation not found");
        }
    }
}
