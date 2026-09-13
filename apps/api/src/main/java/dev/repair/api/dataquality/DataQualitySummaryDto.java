package dev.repair.api.dataquality;

import java.util.List;

public record DataQualitySummaryDto(
        long manufacturers,
        long models,
        long variants,
        long rawRecords,
        long totalIssues,
        List<RuleCountDto> issuesByRule,
        List<RuleCountDto> issuesBySeverity
) {
    public record RuleCountDto(String key, long count) {
    }
}
