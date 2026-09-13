package dev.repair.api.chat;

public record RetrievedChunk(
        long chunkId,
        long documentId,
        String documentTitle,
        String documentProvenance,
        String documentSourceUrl,
        String heading,
        String sectionPath,
        Integer pageNumber,
        String content,
        Double vectorScore,
        Double textScore,
        double fusedScore
) {
}
