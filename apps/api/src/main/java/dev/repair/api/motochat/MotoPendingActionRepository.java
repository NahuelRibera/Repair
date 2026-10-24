package dev.repair.api.motochat;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Proposed chat writes awaiting the rider's confirmation. Every method
 * filters by user_id, and a pending action can be resolved exactly once. */
@Repository
public class MotoPendingActionRepository {

    /** A pending action as stored. */
    public record PendingAction(long id, long garageVehicleId, double odometerKm, String serviceType) {
    }

    private final JdbcClient jdbcClient;

    public MotoPendingActionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public long createOdometerUpdate(long userId, long sessionId, long garageVehicleId, double odometerKm, String serviceType) {
        return jdbcClient.sql(
                        """
                        INSERT INTO moto_pending_actions (user_id, session_id, garage_vehicle_id, action_type, odometer_km, service_type)
                        VALUES (:userId, :sessionId, :garageVehicleId, 'odometer_update', :odometerKm, :serviceType)
                        RETURNING id
                        """)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("garageVehicleId", garageVehicleId)
                .param("odometerKm", odometerKm)
                .param("serviceType", serviceType)
                .query(Long.class)
                .single();
    }

    /** Moves a still-pending action of this user's session to {@code newStatus}
     * and returns it; empty when it does not exist, is not theirs, or was
     * already resolved. */
    public Optional<PendingAction> resolve(long userId, long sessionId, long id, String newStatus) {
        return jdbcClient.sql(
                        """
                        UPDATE moto_pending_actions
                        SET status = :status, resolved_at = now()
                        WHERE id = :id AND user_id = :userId AND session_id = :sessionId AND status = 'pending'
                        RETURNING id, garage_vehicle_id, odometer_km, service_type
                        """)
                .param("status", newStatus)
                .param("id", id)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .query((rs, rowNum) -> new PendingAction(
                        rs.getLong("id"), rs.getLong("garage_vehicle_id"), rs.getDouble("odometer_km"), rs.getString("service_type")))
                .optional();
    }

    /** Marks a still-pending action of this user's session as expired when it
     * was created before {@code oldestConfirmable}; true when it was expired
     * by this call. */
    public boolean expireIfStale(long userId, long sessionId, long id, Instant oldestConfirmable) {
        return jdbcClient.sql(
                        """
                        UPDATE moto_pending_actions
                        SET status = 'expired', resolved_at = now()
                        WHERE id = :id AND user_id = :userId AND session_id = :sessionId AND status = 'pending'
                          AND created_at < :cutoff
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("cutoff", java.sql.Timestamp.from(oldestConfirmable))
                .update() > 0;
    }

    public void markFailed(long id) {
        jdbcClient.sql("UPDATE moto_pending_actions SET status = 'failed' WHERE id = :id").param("id", id).update();
    }
}
