package dev.visitors.run;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * THE PROPERTY THAT HAS TO SURVIVE A NON-DETERMINISTIC MODEL.
 *
 * minimart's seeded population derives every order id from
 * (runId, agentId, tick, step), which is why replaying a window creates nothing
 * new rather than duplicating everything. Putting a language model in the loop
 * threatens that, because the model's output varies, and the obvious
 * implementation lets the model's own words reach the id.
 *
 * The separation that saves it: the model may choose WHAT to do, and it may
 * never choose the identity of the doing. The key is a pure function of the
 * POSITION in the run, so the same visitor at the same tick and step always
 * sends the same key, whatever the model said this time. Prose can vary while
 * effects stay pinned.
 *
 * The runId is inside the key deliberately, so two runs cannot collide. It is
 * also enforced by a unique index on platform_calls, because a derivation that
 * dropped it would still look entirely plausible in the code and would silently
 * merge two experiments.
 */
public final class Keys {

    private Keys() {}

    public static String idem(UUID runId, int visitorId, int tick, int step) {
        return runId + ":" + visitorId + ":" + tick + ":" + step;
    }

    public static UUID uuid(UUID runId, int visitorId, int tick, int step, String label) {
        return UUID.nameUUIDFromBytes(
                (label + ':' + idem(runId, visitorId, tick, step)).getBytes(StandardCharsets.UTF_8));
    }

    /** How many visitors one run may have before it would overlap the next. */
    public static final long RUN_STRIDE = 1_000;

    /** How many distinct run slots exist inside the declared identity space. */
    public static final long RUN_SLOTS = 1_000_000;

    /**
     * A visitor's identity on a platform: disjoint from other populations, AND
     * disjoint from this experiment's own past.
     *
     * The base comes from the MANIFEST, because it is a fact about the platform.
     * minimart's own seeded population uses customer ids from 1000 up, and its
     * subscribe endpoint is idempotent per (tenant, customer, variant): it hands
     * back whatever subscription that customer already holds. A visitor reusing
     * those ids would land on a subscription somebody else created, inherit its
     * renewal date, and every measurement afterwards would be of a subscription
     * the visitor never made.
     *
     * THE RUN IS IN HERE FOR THE SAME REASON, and this half was learned the
     * embarrassing way. With identity = base + visitorId, the first run of the
     * end-to-end task passed and the second failed: visitor 7 was the same
     * customer both times, so run two opened by finding run one's cancelled
     * subscription still sitting there. A run that measures its own history is
     * not measuring anything, and the failure only appeared because the test
     * happened to be run twice.
     *
     * The space is finite and the slot is a hash, so two runs can collide. With
     * a million slots that is likely after a few thousand runs, which is stated
     * here rather than discovered later: this buys independence between
     * consecutive runs, not a guarantee across all runs ever.
     */
    public static long identity(long base, UUID runId, int visitorId) {
        if (visitorId >= RUN_STRIDE) {
            throw new IllegalArgumentException(
                    "visitor " + visitorId + " would overflow into the next run's identity slice");
        }
        long slot = Math.floorMod(runId.hashCode(), RUN_SLOTS);
        return base + slot * RUN_STRIDE + visitorId;
    }
}
