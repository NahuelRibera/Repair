package dev.repair.api.chat;

import java.util.List;

/**
 * The structured contract every assistant turn conforms to (validated
 * against the OpenAI JSON schema in DiagnosticResponseSchema, then again
 * server-side in ChatOrchestrationService before it is ever persisted or
 * returned to the browser). Qualitative language only — retrieval
 * similarity is never presented as a probability of correctness.
 */
public record DiagnosticAnswer(
        String answerType, // clarification | guidance | insufficient_evidence | safety_referral
        String summary,
        List<String> confirmedSymptoms,
        List<String> followUpQuestions,
        List<Hypothesis> hypotheses,
        List<String> safeChecks,
        List<String> cautions,
        List<String> missingInformation,
        List<Long> sourceChunkIds
) {
    public record Hypothesis(
            String description,
            String reasoning,
            List<Long> evidenceChunkIds
    ) {
    }
}
