package dev.visitors.budget;

import dev.visitors.core.Db;
import dev.visitors.core.Ledger;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * THE SPEND LEDGER · why an unbounded agent loop is safe here.
 *
 * A language model is a metered external dependency, and the usual answer to
 * "stop it spending too much" is a counter in application code, or a cap set in
 * the vendor's dashboard. Both are the wrong shape. A counter is a promise
 * someone has to remember to keep, and it is exactly the thing a retry storm or
 * an accidental recursion walks straight through. A dashboard cap is invisible
 * to the system, fails the run in an uncontrolled place, and cannot tell you
 * what an experiment cost.
 *
 * So spending is a LEDGER, and the guarantee is a constraint:
 *
 *     a run is FUNDED before it starts, by a transfer from treasury,
 *     every model call debits that run's account,
 *     and every non-treasury balance carries a non-negative CHECK.
 *
 * "Cannot overspend" is now the same sentence as minimart's "cannot oversell"
 * and minibank's "cannot overdraw": one check, under an ordered row lock,
 * enforced by the database whatever the agent loop does. No bug above this line
 * can spend money that was never granted.
 *
 * AUTHORIZE, CAPTURE, VOID, because the cost is not known until afterwards.
 * Output tokens cannot be counted before the response exists, so the only
 * correct shape is the card lifecycle: hold an upper bound derived from
 * max_tokens, settle the real amount when it returns, and release the
 * difference. A call that times out must void, or a run slowly starves itself
 * of budget it never actually spent.
 */
public final class Budget {

    public static final String CURRENCY = "MICRO_EUR";
    public static final String TREASURY = "treasury";

    /** Raised when a call cannot be authorised because the run cannot afford
     *  its upper bound. This is a refusal BEFORE the spend, not a failure
     *  after it: the model is never called. */
    public static class Exhausted extends RuntimeException {
        public Exhausted(String runAccount) { super("budget exhausted: " + runAccount); }
    }

    private Budget() {}

    public static String runAccount(UUID runId) { return "run:" + runId; }

    /** Fund a run. Treasury is the one account allowed to go negative: its
     *  balance is the mirror of everything ever handed out. */
    public static void fund(UUID runId, BigDecimal microEur, Instant at) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                Ledger.ensureAccount(c, TREASURY, "external", CURRENCY);
                Ledger.ensureAccount(c, runAccount(runId), "internal", CURRENCY);
                UUID txId = UUID.nameUUIDFromBytes(("fund:" + runId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Ledger.claimTx(c, txId, "fund", at)) {
                    Ledger.post(c, txId, at, List.of(
                            new Ledger.Leg(TREASURY, microEur.negate()),
                            new Ledger.Leg(runAccount(runId), microEur)));
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    /**
     * Reserve an upper bound before the call. Throws Exhausted if the run
     * cannot cover it, and in that case NOTHING is spent and the model is never
     * reached: the refusal happens before the cost is incurred, which is the
     * whole point of authorising rather than checking afterwards.
     *
     * The hold moves money out of the run account into a pooled holding
     * account, so a concurrent authorisation sees the reduced balance
     * immediately. Two callers racing the last of the budget cannot both
     * succeed, because the check and the debit are the same locked operation.
     */
    public static UUID authorize(UUID runId, BigDecimal upperBound, Instant at) throws SQLException {
        UUID holdId = UUID.randomUUID();
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                Ledger.ensureAccount(c, held(runId), "internal", CURRENCY);
                UUID txId = UUID.nameUUIDFromBytes(("auth:" + holdId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Ledger.claimTx(c, txId, "authorize", at);
                try {
                    Ledger.post(c, txId, at, List.of(
                            new Ledger.Leg(runAccount(runId), upperBound.negate()),
                            new Ledger.Leg(held(runId), upperBound)));
                } catch (Ledger.Insufficient e) {
                    c.rollback();
                    throw new Exhausted(runAccount(runId));
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO holds(id, run_id, authorized, state, business_at) VALUES (?,?,?, 'AUTHORIZED', ?)")) {
                    ps.setObject(1, holdId); ps.setObject(2, runId);
                    ps.setBigDecimal(3, upperBound); ps.setTimestamp(4, java.sql.Timestamp.from(at));
                    ps.executeUpdate();
                }
                c.commit();
                return holdId;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            }
        }
    }

    /**
     * Settle the real cost and give back the difference.
     *
     * When the real cost EXCEEDS the hold, the extra is drawn from the run
     * account and recorded as an overage. The first version refused this with a
     * CHECK, on the theory that a call costing more than authorised is a fact
     * worth failing on. It is, but failing the CAPTURE is the wrong way to
     * surface it: the vendor has already been paid, and a ledger that declines
     * to record a cost that really happened makes real spending look free. The
     * estimator being wrong is a fact to write down, not to reject.
     */
    public static void capture(UUID holdId, BigDecimal actual, Instant at) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                Hold h = load(c, holdId);
                if (h == null || !"AUTHORIZED".equals(h.state())) { c.rollback(); return; }  // idempotent

                Ledger.ensureAccount(c, vendor(), "external", CURRENCY);
                UUID txId = UUID.nameUUIDFromBytes(("capture:" + holdId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                BigDecimal refund = h.authorized().subtract(actual);
                BigDecimal overage = refund.signum() < 0 ? refund.negate() : BigDecimal.ZERO;
                if (Ledger.claimTx(c, txId, "capture", at)) {
                    // the hold is released in full, the vendor is paid the real
                    // amount, and the difference settles against the run either
                    // way round: a refund when the estimate was generous, a
                    // further debit when it was not
                    Ledger.post(c, txId, at, refund.signum() == 0
                            ? List.of(new Ledger.Leg(held(h.runId()), actual.negate()),
                                      new Ledger.Leg(vendor(), actual))
                            : List.of(new Ledger.Leg(held(h.runId()), h.authorized().negate()),
                                      new Ledger.Leg(vendor(), actual),
                                      new Ledger.Leg(runAccount(h.runId()), refund)));
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE holds SET state = 'CAPTURED', captured = ?, overage = ? WHERE id = ? AND state = 'AUTHORIZED'")) {
                    ps.setBigDecimal(1, actual); ps.setBigDecimal(2, overage); ps.setObject(3, holdId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    /** The call failed or timed out. Return the hold in full. A void that does
     *  not restore the budget exactly is a slow leak that looks like nothing. */
    public static void voidHold(UUID holdId, Instant at) throws SQLException {
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try {
                Hold h = load(c, holdId);
                if (h == null || !"AUTHORIZED".equals(h.state())) { c.rollback(); return; }  // idempotent

                UUID txId = UUID.nameUUIDFromBytes(("void:" + holdId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (Ledger.claimTx(c, txId, "void", at)) {
                    Ledger.post(c, txId, at, List.of(
                            new Ledger.Leg(held(h.runId()), h.authorized().negate()),
                            new Ledger.Leg(runAccount(h.runId()), h.authorized())));
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE holds SET state = 'VOIDED' WHERE id = ? AND state = 'AUTHORIZED'")) {
                    ps.setObject(1, holdId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }

    /** How often the estimator was too low. A rising number means the upper
     *  bound is not an upper bound, which is worth knowing before it becomes
     *  the reason a run cannot pay for its last call. */
    public static long overages(UUID runId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM holds WHERE run_id = ? AND overage > 0")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    public static BigDecimal available(UUID runId) throws SQLException {
        try (Connection c = Db.open()) {
            return Ledger.balance(c, runAccount(runId));
        }
    }

    public static BigDecimal spent(UUID runId) throws SQLException {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(captured), 0) FROM holds WHERE run_id = ? AND state = 'CAPTURED'")) {
            ps.setObject(1, runId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getBigDecimal(1); }
        }
    }

    // ---------------------------------------------------------------- internals

    private record Hold(UUID id, UUID runId, BigDecimal authorized, String state) {}

    private static Hold load(Connection c, UUID holdId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT run_id, authorized, state FROM holds WHERE id = ? FOR UPDATE")) {
            ps.setObject(1, holdId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Hold(holdId, (UUID) rs.getObject(1), rs.getBigDecimal(2), rs.getString(3));
            }
        }
    }

    private static String held(UUID runId) { return "held:" + runId; }
    private static String vendor() { return "vendor"; }
}
