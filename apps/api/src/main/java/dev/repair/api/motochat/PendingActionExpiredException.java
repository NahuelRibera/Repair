package dev.repair.api.motochat;

/** The rider tried to confirm a proposed write after its expiry window had
 * passed. The action is stored as expired and nothing was written. */
public class PendingActionExpiredException extends RuntimeException {

    public static final String CODE = "pending_action_expired";

    public PendingActionExpiredException(String message) {
        super(message);
    }
}
