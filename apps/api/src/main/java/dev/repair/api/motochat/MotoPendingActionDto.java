package dev.repair.api.motochat;

/** A write the assistant proposed that has not happened yet — the rider
 * must confirm it (or dismiss it) before anything is stored. */
public record MotoPendingActionDto(long id, String type, double odometerKm, String serviceType, String summary) {
}
