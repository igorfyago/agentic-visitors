package dev.visitors.run;

import dev.visitors.core.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * WRITE DOWN THE INTENTION BEFORE THE SOCKET OPENS.
 *
 * The obvious implementation sends the request and then records what happened.
 * It reads perfectly and it has a hole: crash between the send and the write,
 * and an effect exists on the platform that this system has no record of ever
 * having caused. Nothing detects that afterwards, because the only evidence
 * that the call happened is the row that was never written.
 *
 * So the PENDING row is committed on its own connection BEFORE the request
 * leaves, and settled afterwards. A crash then leaves a PENDING row, which is
 * an honest statement of exactly what is known: we tried, and we do not know.
 * A PENDING row that never settles is a question a human can answer later; a
 * missing row is a question nobody knows to ask.
 *
 * The unique index on idempotency_key is the second half. The key is derived
 * from the position in the run, so claiming it is also how replaying a window
 * creates nothing new: the second attempt finds the row taken and does not
 * send.
 */
public final class Calls {

    private Calls() {}

    /**
     * Claim the right to make this call. Empty means it was already claimed,
     * so replaying this position must not send anything.
     */
    public static OptionalLong claim(UUID runId, long decisionId, String platform, String action,
                                     String idempotencyKey, Instant businessAt) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO platform_calls(run_id, decision_id, platform, action, idempotency_key,
                                                outcome, business_at)
                     VALUES (?,?,?,?,?, 'PENDING', ?)
                     ON CONFLICT (idempotency_key) DO NOTHING""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setObject(1, runId); ps.setLong(2, decisionId); ps.setString(3, platform);
            ps.setString(4, action); ps.setString(5, idempotencyKey);
            ps.setTimestamp(6, java.sql.Timestamp.from(businessAt));
            if (ps.executeUpdate() == 0) return OptionalLong.empty();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return OptionalLong.of(rs.getLong(1));
            }
        }
    }

    /** Settle a claimed call with what actually came back. */
    public static void settle(long id, Integer status, String outcome, String reason,
                              String response, int latencyMs) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE platform_calls SET status = ?, outcome = ?, reason = ?, response = ? WHERE id = ?")) {
            if (status == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, status);
            ps.setString(2, outcome); ps.setString(3, reason);
            ps.setString(4, response == null ? null : response.substring(0, Math.min(response.length(), 4000)));
            ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** Calls still unanswered. Not a failure count: a question count. */
    public static long pending(UUID runId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM platform_calls WHERE run_id = ? AND outcome = 'PENDING'")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }
}
