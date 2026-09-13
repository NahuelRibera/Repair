package dev.repair.api.catalogue;

public record VariantDto(
        long id,
        long modelId,
        String variantName,
        String slug,
        Integer yearStart,
        Integer yearEnd,
        String fuelType,
        String driveType,
        String gearbox,
        String qualityStatus
) {
}
