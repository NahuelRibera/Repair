package dev.repair.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identifies the single "supported demo vehicle" by real catalogue
 * criteria (manufacturer, model, a substring of the variant name, and the
 * year range start) — never by a hardcoded numeric id. The API resolves
 * this against the live database on every request to
 * {@code GET /api/vehicles/demo}, so if the catalogue is ever re-imported
 * and the match changes or disappears, the endpoint fails loudly (404)
 * instead of silently pointing at the wrong vehicle. See
 * CatalogueRepository.findDemoVariant and docs/planning/status.md for the
 * bug this fixes: the frontend previously took the first row of
 * /api/vehicles/covered (sorted by year ascending), which for "BMW 3
 * Series Sedan" is the oldest generation linked via any document —
 * including the six generic documents that are deliberately linked
 * model-wide across every BMW 3 Series generation — not the curated E90
 * 320d demo variant.
 */
@ConfigurationProperties(prefix = "repair.demo")
public record DemoVehicleProperties(
        String manufacturerName,
        String modelName,
        String variantNameContains,
        Integer yearStart
) {
}
