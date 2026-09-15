package dev.repair.api.motochat;

/**
 * One row of the controlled-action audit trail for a single chat turn —
 * every proposal the model made, whether it was executed or rejected, and
 * why. Stored in moto_rag_runs.actions_taken (debug-only — see
 * MotoRagRunDebugDto.actionsTakenJson) so Evidence & Debug can show
 * "actions proposed / accepted / rejected / reason for rejection" without
 * cluttering the normal customer-facing answer, which only ever sees the
 * executed subset (MotoChatTurnResult.actionsTaken).
 */
public record ProposalAuditEntry(String proposalType, String intent, String verdict, String reason) {

    static ProposalAuditEntry executed(String proposalType, String intent) {
        return new ProposalAuditEntry(proposalType, intent, "executed", null);
    }

    static ProposalAuditEntry rejected(String proposalType, String intent, String reason) {
        return new ProposalAuditEntry(proposalType, intent, "rejected", reason);
    }
}
