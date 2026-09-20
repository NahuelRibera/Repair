package dev.repair.api.motochat;

public record MotoRetrievedChunk(
        long chunkId, long documentId, String section, String subsection, String category,
        String heading, String sectionPath, String content, Double vectorScore, Double textScore, double fusedScore
) {
}
