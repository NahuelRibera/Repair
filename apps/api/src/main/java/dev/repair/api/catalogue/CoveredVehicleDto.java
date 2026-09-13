package dev.repair.api.catalogue;

/** A variant that has at least one linked knowledge-corpus document — i.e.
 * an actually-supported demo scenario, not just a catalogue entry. */
public record CoveredVehicleDto(
        long variantId,
        String manufacturerName,
        String modelName,
        String variantName,
        Integer yearStart,
        Integer yearEnd
) {
}
