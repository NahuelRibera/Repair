package dev.repair.api.garage;

import dev.repair.api.common.BadRequestException;
import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.VisitorContext;
import dev.repair.api.motorcycle.MotorcycleCatalogRepository;
import dev.repair.api.motorcycle.ModelDetailDto;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GarageVehicleController {

    private final GarageVehicleRepository garageVehicleRepository;
    private final MotorcycleCatalogRepository catalogRepository;
    private final VisitorContext visitor;

    public GarageVehicleController(
            GarageVehicleRepository garageVehicleRepository, MotorcycleCatalogRepository catalogRepository,
            VisitorContext visitor
    ) {
        this.garageVehicleRepository = garageVehicleRepository;
        this.catalogRepository = catalogRepository;
        this.visitor = visitor;
    }

    @PostMapping("/api/garage/vehicles")
    public ResponseEntity<GarageVehicleDto> create(@Valid @RequestBody CreateGarageVehicleRequest request) {
        ModelDetailDto model = catalogRepository.findModelDetail(request.modelId())
                .orElseThrow(() -> new BadRequestException("Unknown motorcycle model"));
        if (!catalogRepository.hasKnowledgeCoverage(request.modelId(), request.year())) {
            throw new BadRequestException(
                    "No knowledge coverage for " + model.manufacturerName() + " " + model.name() + " " + request.year());
        }

        boolean allowDuplicate = Boolean.TRUE.equals(request.allowDuplicate());
        if (!allowDuplicate) {
            var existing = garageVehicleRepository.findExisting(visitor.getVisitorId(), request.modelId(), request.year());
            if (existing.isPresent()) {
                GarageVehicleDto dto = garageVehicleRepository.find(visitor.getVisitorId(), existing.get())
                        .orElseThrow(() -> new IllegalStateException("Garage vehicle vanished immediately after lookup"));
                return ResponseEntity.ok(dto);
            }
        }

        long id = garageVehicleRepository.create(visitor.getVisitorId(), request.modelId(), request.year(), null, request.nickname());
        GarageVehicleDto created = garageVehicleRepository.find(visitor.getVisitorId(), id)
                .orElseThrow(() -> new IllegalStateException("Garage vehicle vanished immediately after creation"));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/api/garage/vehicles")
    public List<GarageVehicleDto> list() {
        return garageVehicleRepository.list(visitor.getVisitorId());
    }

    @GetMapping("/api/garage/vehicles/{id}")
    public GarageVehicleDto get(@PathVariable long id) {
        return garageVehicleRepository.find(visitor.getVisitorId(), id)
                .orElseThrow(() -> new NotFoundException("Garage vehicle not found"));
    }

    @PatchMapping("/api/garage/vehicles/{id}")
    public GarageVehicleDto update(@PathVariable long id, @RequestBody UpdateGarageVehicleRequest request) {
        if (request.currentOdometerKm() != null && request.currentOdometerKm() < 0) {
            throw new BadRequestException("Odometer cannot be negative");
        }
        boolean updated = garageVehicleRepository.update(visitor.getVisitorId(), id, request.nickname(), request.currentOdometerKm());
        if (!updated) {
            throw new NotFoundException("Garage vehicle not found");
        }
        return garageVehicleRepository.find(visitor.getVisitorId(), id)
                .orElseThrow(() -> new NotFoundException("Garage vehicle not found"));
    }

    @DeleteMapping("/api/garage/vehicles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        if (!garageVehicleRepository.softDelete(visitor.getVisitorId(), id)) {
            throw new NotFoundException("Garage vehicle not found");
        }
    }
}
