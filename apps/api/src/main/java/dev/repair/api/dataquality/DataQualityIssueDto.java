package dev.repair.api.dataquality;

public record DataQualityIssueDto(
        long id,
        String rule,
        String field,
        String observedValuePreview,
        String severity,
        String explanation,
        String manufacturerName,
        String modelName,
        String variantName
) {
}
