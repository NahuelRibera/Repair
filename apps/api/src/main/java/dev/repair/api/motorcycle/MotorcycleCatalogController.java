package dev.repair.api.motorcycle;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, fully dynamic catalog browsing (manufacturer -> model ->
 * year). See docs/repair-v2-architecture.md section 3 for why this has no
 * write path and no hardcoded vehicle anywhere. */
@RestController
public class MotorcycleCatalogController {

    private final MotorcycleCatalogRepository repository;

    public MotorcycleCatalogController(MotorcycleCatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/motorcycles/manufacturers")
    public List<ManufacturerDto> manufacturers() {
        return repository.listManufacturers();
    }

    @GetMapping("/api/motorcycles/manufacturers/{manufacturerId}/models")
    public List<ModelDto> models(@PathVariable long manufacturerId) {
        return repository.listModels(manufacturerId);
    }

    @GetMapping("/api/motorcycles/models/{modelId}/years")
    public List<Integer> years(@PathVariable long modelId) {
        return repository.listYears(modelId);
    }
}
