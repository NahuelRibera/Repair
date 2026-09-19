package dev.repair.api.auth;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Authenticated Repair users. google_sub (the OIDC "sub" claim) is the
 * external identity key — never email, which can change or be reused
 * across Google accounts. See docs/authentication.md.
 */
@Repository
public class AppUserRepository {

    private static final String SELECT = """
            SELECT id, google_sub, email, display_name, google_picture_url, created_at, updated_at
            FROM app_users
            """;

    private final JdbcClient jdbcClient;

    public AppUserRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AppUserDto> findByGoogleSub(String googleSub) {
        return jdbcClient.sql(SELECT + " WHERE google_sub = :sub")
                .param("sub", googleSub)
                .query(AppUserRepository::map)
                .optional();
    }

    public Optional<AppUserDto> findById(long id) {
        return jdbcClient.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(AppUserRepository::map)
                .optional();
    }

    public AppUserDto create(String googleSub, String email, String displayName, String googlePictureUrl) {
        long id = jdbcClient.sql(
                        """
                        INSERT INTO app_users (google_sub, email, display_name, google_picture_url)
                        VALUES (:sub, :email, :displayName, :pictureUrl)
                        RETURNING id
                        """)
                .param("sub", googleSub)
                .param("email", email)
                .param("displayName", displayName)
                .param("pictureUrl", googlePictureUrl)
                .query(Long.class)
                .single();
        return findById(id).orElseThrow(() -> new IllegalStateException("app_user vanished immediately after creation"));
    }

    /** Called on every login (not every request) to keep profile fields
     * fresh if the rider's Google name/picture changed since last time. */
    public void updateProfile(long id, String email, String displayName, String googlePictureUrl) {
        jdbcClient.sql(
                        """
                        UPDATE app_users
                        SET email = :email, display_name = :displayName, google_picture_url = :pictureUrl, updated_at = now()
                        WHERE id = :id
                        """)
                .param("email", email)
                .param("displayName", displayName)
                .param("pictureUrl", googlePictureUrl)
                .param("id", id)
                .update();
    }

    private static AppUserDto map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AppUserDto(
                rs.getLong("id"), rs.getString("google_sub"), rs.getString("email"), rs.getString("display_name"),
                rs.getString("google_picture_url"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)
        );
    }
}
