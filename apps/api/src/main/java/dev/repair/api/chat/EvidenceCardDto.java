package dev.repair.api.chat;

public record EvidenceCardDto(
        long chunkId,
        long documentId,
        String documentTitle,
        String documentProvenance,
        String documentSourceUrl,
        String heading,
        String sectionPath,
        Integer pageNumber,
        String excerpt,
        Double vectorScore,
        Double textScore,
        double fusedScore
) {
}
