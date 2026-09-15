package dev.repair.api.motochat;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic, model-independent safety net against the "false positive
 * maintenance write" bug class: a hypothetical, uncertain, planned-future,
 * or merely-asked-about statement must never create a maintenance event or
 * move the odometer, even if the model itself misjudges its own
 * `intent` classification (MotoDiagnosticAnswer.ProposedMaintenanceEvent).
 *
 * This is intentionally a second, independent layer on top of the
 * model-reported intent enum — "backend rules remain the final authority"
 * (see docs/maintenance-tracking.md). It is deliberately conservative: a
 * false positive here only costs an extra confirmation turn for the
 * rider; a false negative would silently corrupt garage data, which is
 * the worse failure mode by far.
 */
final class ActionIntentGuard {

    private ActionIntentGuard() {
    }

    private static final List<Pattern> BLOCKING_PATTERNS = List.of(
            Pattern.compile("\\bif\\s+i\\b"),
            Pattern.compile("\\bwhat\\s+if\\b"),
            Pattern.compile("\\bwould\\s+(be|need|require)\\b"),
            Pattern.compile("\\bhypothetical"),
            Pattern.compile("\\bsuppose\\b"),
            Pattern.compile("\\bi\\s*['’]?\\s*think\\b"),
            Pattern.compile("\\bi\\s+believe\\b"),
            Pattern.compile("\\bnot\\s+sure\\b"),
            Pattern.compile("\\bmaybe\\b"),
            Pattern.compile("\\bprobably\\b"),
            Pattern.compile("\\bpossibly\\b"),
            Pattern.compile("\\bperhaps\\b"),
            Pattern.compile("\\bi\\s+guess\\b"),
            Pattern.compile("\\bno\\s+idea\\b"),
            Pattern.compile("\\bdon\\W?t\\s+remember\\b"),
            Pattern.compile("\\bnot\\s+certain\\b"),
            Pattern.compile("\\bi\\s+should\\b"),
            Pattern.compile("\\bi\\s+might\\b"),
            Pattern.compile("\\bplan(ning)?\\s+to\\b"),
            Pattern.compile("\\bgoing\\s+to\\b"),
            Pattern.compile("\\btomorrow\\b"),
            Pattern.compile("\\bsoon\\b"),
            Pattern.compile("\\bnext\\s+(week|month)\\b"),
            Pattern.compile("\\beventually\\b")
    );

    /** True if the rider's own message this turn contains language that
     * makes ANY proposed action (maintenance event or odometer update)
     * unsafe to execute this turn, regardless of what the model proposed. */
    static boolean blocksAction(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String lower = userText.toLowerCase(Locale.ROOT);
        for (Pattern pattern : BLOCKING_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }
}
