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
        List<String> followUpQuestions,
        List<String> safeChecks,
        List<String> cautions,
        List<Long> sourceChunkIds,
        ProposedMaintenanceEvent proposedMaintenanceEvent,
        ProposedOdometerUpdate proposedOdometerUpdate,
        ProposedPreference proposedPreference
) {
    public record ProposedMaintenanceEvent(String serviceType, Double odometerKm, String performedAt, String notes) {
    }

    public record ProposedOdometerUpdate(double odometerKm) {
    }

    public record ProposedPreference(String preferenceType, String context, Double frontKpa, Double rearKpa) {
    }
}
