package dev.repair.api.catalogue;

public record SpecsDto(
        Double powerHp,
        Double powerKw,
        Double powerBhp,
        Double torqueNm,
        Double torqueLbft,
        Double topSpeedKmh,
        Double acceleration0100KmhS,
        Double displacementCm3,
        Double weightKg,
        String cylinderLayout,
        Integer cylinderCount,
        Double co2EmissionsGKm,
        Double fuelConsumptionCombinedL100km
) {
}
