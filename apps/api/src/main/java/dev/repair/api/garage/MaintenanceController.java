package dev.repair.api.garage;

import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public class MaintenanceController {

    private final GarageVehicleRepository garageVehicleRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final VehiclePreferenceRepository preferenceRepository;
    private final MaintenanceStatusService statusService;
    private final VisitorContext visitor;
    private final ObjectMapper objectMapper;

    public MaintenanceController(
            GarageVehicleRepository garageVehicleRepository, MaintenanceRepository maintenanceRepository,
            VehiclePreferenceRepository preferenceRepository, MaintenanceStatusService statusService,
            VisitorContext visitor, ObjectMapper objectMapper
    ) {
        this.garageVehicleRepository = garageVehicleRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.preferenceRepository = preferenceRepository;
        this.statusService = statusService;
        this.visitor = visitor;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/garage/vehicles/{id}/maintenance")
    public List<MaintenanceEventDto> listMaintenance(@PathVariable long id) {
        requireOwned(id);
        return maintenanceRepository.listForOwnedVehicle(visitor.getVisitorId(), id);
    }

    @PostMapping("/api/garage/vehicles/{id}/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceEventDto createMaintenance(@PathVariable long id, @Valid @RequestBody CreateMaintenanceEventRequest request) {
        requireOwned(id);
        if (!ServiceType.isValid(request.serviceType())) {
            throw new BadRequestException("Unknown service type: " + request.serviceType());
        }
        if (request.odometerKm() == null && request.performedAt() == null) {
            throw new BadRequestException("A maintenance event needs an odometer reading, a date, or both");
        }
        if (request.odometerKm() != null && (request.odometerKm() < 0 || request.odometerKm() > 500_000)) {
            throw new BadRequestException("Odometer reading is out of range");
        }
        long eventId = maintenanceRepository.createEvent(
                id, request.serviceType(), request.odometerKm(), request.performedAt(), request.notes(), "manual"
        );
        return maintenanceRepository.listForOwnedVehicle(visitor.getVisitorId(), id).stream()
                .filter(e -> e.id() == eventId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Maintenance event vanished immediately after creation"));
    }

    @GetMapping("/api/garage/vehicles/{id}/dashboard")
    public List<MaintenanceStatusService.StatusCard> dashboard(@PathVariable long id) {
        var vehicle = garageVehicleRepository.find(visitor.getVisitorId(), id)
                .orElseThrow(() -> new NotFoundException("Garage vehicle not found"));
        var latestByType = maintenanceRepository.latestEventByType(id);
        return statusService.buildDashboard(vehicle, latestByType);
    }

    @GetMapping("/api/garage/vehicles/{id}/preferences")
    public List<VehiclePreferenceDto> listPreferences(@PathVariable long id) {
        requireOwned(id);
        return preferenceRepository.listForOwnedVehicle(visitor.getVisitorId(), id);
    }

    @PostMapping("/api/garage/vehicles/{id}/preferences")
    public List<VehiclePreferenceDto> savePreference(@PathVariable long id, @Valid @RequestBody SaveVehiclePreferenceRequest request) {
        requireOwned(id);
        if (!"TIRE_PRESSURE".equals(request.preferenceType())) {
            throw new BadRequestException("Unknown preference type: " + request.preferenceType());
        }
        if (!java.util.Set.of("ROAD", "OFF_ROAD", "WET", "TRACK").contains(request.context())) {
            throw new BadRequestException("Unknown preference context: " + request.context());
        }
        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(request.data());
        } catch (Exception e) {
            throw new BadRequestException("Invalid preference data");
        }
        preferenceRepository.upsert(id, request.preferenceType(), request.context(), dataJson);
        return preferenceRepository.listForOwnedVehicle(visitor.getVisitorId(), id);
    }

    private void requireOwned(long garageVehicleId) {
        if (!garageVehicleRepository.isOwned(visitor.getVisitorId(), garageVehicleId)) {
            throw new NotFoundException("Garage vehicle not found");
        }
    }
}
