package dev.repair.api.motochat;

import java.util.List;

/**
 * The structured contract every motorcycle-assistant turn conforms to
 * (strict JSON-schema validated by OpenAI, then re-validated server-side —
 * see MotoChatOrchestrationService.validateCitations and
 * .validateProposedActions). The three "proposed*" fields are the
 * controlled-action surface described in
 * docs/repair-v2-architecture.md section 5: the model may propose a
 * write, but every proposal is validated and executed by the backend —
 * never by model-generated SQL, and never persisted when null/absent.
 */
public record MotoDiagnosticAnswer(
        String answerType, // clarification | guidance | insufficient_evidence | safety_referral
        String summary,
        List<String> confirmedFacts,
        List<String> contextUsed,
        List<String> followUpQuestions,
        List<String> safeChecks,
        List<String> cautions,
        List<Long> sourceChunkIds,
        ProposedMaintenanceEvent proposedMaintenanceEvent,
        ProposedOdometerUpdate proposedOdometerUpdate,
        ProposedPreference proposedPreference
) {
    /**
     * The rider's stated intent behind a proposed action, classified by the
     * model and re-validated by a deterministic backend guard (see
     * MotoChatOrchestrationService.containsHypotheticalOrUncertainLanguage).
     * Only CONFIRMED_COMPLETED may ever result in a database write — every
     * other value is read-only context. This is the fix for the "false
     * positive maintenance write" class of bug: a hypothetical, uncertain,
     * planned, or merely-asked-about mileage/service must never be
     * persisted, no matter how the model phrases its summary.
     */
    public record ProposedMaintenanceEvent(
            String serviceType, Double odometerKm, String performedAt, String notes, String intent
    ) {
    }

    public record ProposedOdometerUpdate(double odometerKm, String intent) {
    }

    public record ProposedPreference(String preferenceType, String context, Double frontKpa, Double rearKpa) {
    }
}
