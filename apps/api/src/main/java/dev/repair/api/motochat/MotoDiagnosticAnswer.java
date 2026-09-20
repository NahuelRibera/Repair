package dev.repair.api.motochat;

import java.util.List;

/**
 * The structured contract every motorcycle-assistant turn conforms to
 * (strict JSON-schema validated by OpenAI, then re-validated server-side —
 * see MotoChatOrchestrationService.validateCitations and
 * .validateProposedActions). The "proposed*" fields are the
 * controlled-action surface described in
 * docs/repair-v2-architecture.md section 5: the model may propose a
 * write, but every proposal is validated and executed by the backend —
 * never by model-generated SQL, and never persisted when null/absent.
 *
 * proposedMaintenanceEvents is a LIST (never a single nullable object):
 * a rider can confirm more than one distinct maintenance action in one
 * message ("I changed the oil and oil filter at 24,000 km") and every
 * independently confirmed action gets its own entry — never collapsed
 * into one, never silently dropped because a slot was already used.
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
        List<ProposedMaintenanceEvent> proposedMaintenanceEvents,
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
     *
     * {@code isCorrection}: true when the rider is amending/correcting a
     * value they (or the assistant, echoing them) already stated earlier
     * in THIS conversation for the same service type — never true for a
     * genuinely new, separate occurrence of that service. When true, the
     * backend updates the most recently recorded event of this service
     * type in place rather than inserting a second, conflicting row (see
     * MotoChatOrchestrationService.validateAndApplyProposedActions and
     * MaintenanceRepository.correctLatestEvent) — this is the fix for the
     * "correction creates a second event instead of replacing the first"
     * class of bug.
     */
    public record ProposedMaintenanceEvent(
            String serviceType, Double odometerKm, String performedAt, String notes, String intent, boolean isCorrection
    ) {
    }

    public record ProposedOdometerUpdate(double odometerKm, String intent) {
    }

    public record ProposedPreference(String preferenceType, String context, Double frontKpa, Double rearKpa) {
    }
}
