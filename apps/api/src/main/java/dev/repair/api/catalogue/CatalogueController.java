package dev.repair.api.catalogue;

import dev.repair.api.common.NotFoundException;
import dev.repair.api.common.PageResult;
import dev.repair.api.config.DemoVehicleProperties;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only catalogue browsing. Deliberately has no dependency on OpenAI —
 * the catalogue must stay usable even when no API key is configured.
 */
@RestController
public class CatalogueController {

    // Comfortably above the current largest single fetch this API needs to
    // satisfy in one page — 112 manufacturers total, and the largest
    // single manufacturer's model count is ~110 (Ford) — so a searchable
    // dropdown can request "everything" in one request and actually get
    // it, while this still stays a real, enforced bound rather than
    // literally unbounded (see /api/data-quality/issues, which can have
    // thousands of rows and must keep paging).
    private static final int MAX_PAGE_SIZE = 500;

    private final CatalogueRepository repository;
    private final DemoVehicleProperties demoVehicleProperties;

    public CatalogueController(CatalogueRepository repository, DemoVehicleProperties demoVehicleProperties) {
        this.repository = repository;
        this.demoVehicleProperties = demoVehicleProperties;
    }

    @GetMapping("/api/manufacturers")
    public PageResult<ManufacturerDto> manufacturers(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return repository.searchManufacturers(search, page, Math.min(size, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/manufacturers/{manufacturerId}/models")
    public PageResult<ModelDto> models(
            @PathVariable long manufacturerId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return repository.searchModels(manufacturerId, search, page, Math.min(size, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/models/{modelId}/variants")
    public List<VariantDto> variants(@PathVariable long modelId) {
        return repository.listVariantsForModel(modelId);
    }

    @GetMapping("/api/variants/{variantId}")
    public VariantDetailDto variantDetail(@PathVariable long variantId) {
        return repository.findVariantDetail(variantId)
                .orElseThrow(() -> new NotFoundException("Vehicle variant not found"));
    }

    @GetMapping("/api/vehicles/covered")
    public List<CoveredVehicleDto> coveredVehicles() {
        return repository.listCoveredVehicles();
    }

    /**
     * The single curated demo vehicle, resolved from real catalogue data
     * against the criteria in DemoVehicleProperties — see that class and
     * CatalogueRepository.findDemoVariant for why this must never be
     * "the first row of /api/vehicles/covered" (that previously resolved
     * to the oldest BMW 3 Series generation linked via a generic,
     * model-wide document, not the curated E90 320d).
     */
    @GetMapping("/api/vehicles/demo")
    public VariantDetailDto demoVehicle() {
        return repository.findDemoVariant(
                        demoVehicleProperties.manufacturerName(),
                        demoVehicleProperties.modelName(),
                        demoVehicleProperties.variantNameContains(),
                        demoVehicleProperties.yearStart()
                )
                .orElseThrow(() -> new NotFoundException(
                        "The configured demo vehicle could not be resolved from the current catalogue"
                ));
    }
}
