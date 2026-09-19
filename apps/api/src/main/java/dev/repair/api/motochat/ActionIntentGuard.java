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
 *
 * {@link #blocksValueMention} is the one callers with a specific proposed
 * number (an odometer reading) should prefer over the older, whole-message
 * {@link #blocksAction}: a real rider message is often more than one
 * sentence, and an unrelated trailing hedge ("...I should get the chain
 * looked at soon") must not veto a clearly confirmed, unrelated statement
 * earlier in the same message ("The odometer now reads 20,000 km."). The
 * check is scoped to whichever sentence(s) actually mention the proposed
 * number; if the number can't be located in any single sentence (e.g. it
 * was never written back verbatim), it conservatively falls back to the
 * whole-message check.
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
            Pattern.compile("\\beventually\\b"),
            // "once/when I reach/hit X km, ..." — a future-conditional
            // mileage, exactly as unsafe to persist as "if I were at X km".
            Pattern.compile("\\b(when|once)\\s+i\\s+(reach|hit|get\\s+to)\\b")
    );

    /** Splits on sentence-ending punctuation followed by whitespace, or a
     * newline — deliberately simple (no NLP dependency); a rider's own
     * chat message is short enough that this is reliable in practice. */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    /** True if the rider's own message this turn contains language that
     * makes ANY proposed action (maintenance event or odometer update)
     * unsafe to execute this turn, regardless of what the model proposed.
     * Whole-message check — use {@link #blocksValueMention} instead when a
     * specific proposed number is available to anchor the check on. */
    static boolean blocksAction(String userText) {
        return matchesAnyPattern(userText);
    }

    /**
     * Value-anchored variant of {@link #blocksAction}: only treats the
     * message as blocking if a hedge/hypothetical/future pattern appears
     * in the SAME sentence as the proposed number, rather than anywhere in
     * a possibly-multi-sentence message. Falls back to the whole-message
     * check when the number can't be located verbatim in any one sentence
     * (conservative by design — never less safe than the old behavior,
     * only more precise when it can be).
     */
    static boolean blocksValueMention(String userText, double anchorValue) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String[] sentences = SENTENCE_BOUNDARY.split(userText);
        String asInt = String.valueOf((long) anchorValue);
        boolean foundValueInASentence = false;
        for (String sentence : sentences) {
            String digitsOnly = sentence.replaceAll("[,\\s]", "");
            if (digitsOnly.contains(asInt)) {
                foundValueInASentence = true;
                if (matchesAnyPattern(sentence)) {
                    return true;
                }
            }
        }
        if (foundValueInASentence) {
            return false;
        }
        return matchesAnyPattern(userText);
    }

    private static boolean matchesAnyPattern(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern pattern : BLOCKING_PATTERNS) {
            if (pattern.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }
}
