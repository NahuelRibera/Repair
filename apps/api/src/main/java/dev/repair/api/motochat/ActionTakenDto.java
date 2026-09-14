package dev.repair.api.motochat;

/** A controlled write that was actually validated and executed this turn
 * — the frontend renders this as a lightweight confirmation toast (e.g.
 * "Oil change saved · 19,000 km"). Never constructed from raw model
 * output; only from what MotoChatOrchestrationService itself wrote. */
public record ActionTakenDto(String type, String serviceType, Double odometerKm, String detail) {
}
