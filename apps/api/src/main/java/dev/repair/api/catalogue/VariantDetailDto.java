package dev.repair.api.catalogue;

public record VariantDetailDto(
        VariantDto variant,
        String manufacturerName,
        String modelName,
        SpecsDto specs,
        boolean hasKnowledgeCoverage
) {
}
