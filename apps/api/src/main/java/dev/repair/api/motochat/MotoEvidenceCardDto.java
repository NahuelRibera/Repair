package dev.repair.api.motochat;

public record MotoEvidenceCardDto(
        long chunkId, long documentId, String section, String subsection, String category,
        String heading, String sectionPath, String excerpt, Double vectorScore, Double textScore, double fusedScore
) {
}
