package dev.visitors.intent;

/**
 * WHY A PROPOSAL WAS NOT EXECUTED.
 *
 * A taxonomy rather than one INVALID bucket, because these call for opposite
 * fixes and a single number would hide which one is needed. UNPARSEABLE and
 * UNKNOWN_INTENT mean the model does not understand the format: a prompt
 * problem. PARAM_UNKNOWN_REF means it understood perfectly and invented an id:
 * a grounding problem. Reporting them together would make a prompt change and a
 * grounding change look identical in the results.
 *
 * Mirrored by a CHECK constraint on decisions.reject_reason, so a new member
 * here without a migration fails loudly instead of silently writing a value
 * nothing downstream knows how to read.
 */
public enum Reject {
    /** No line began with a token from the alphabet. */
    UNPARSEABLE,
    /** Something shaped like an intent, but not one this platform declares. */
    UNKNOWN_INTENT,
    /** A declared parameter the model did not supply. */
    MISSING_PARAM,
    /** A parameter the capability does not declare, or one the runtime owns
     *  and the model tried to set. */
    PARAM_EXTRA,
    /** The wrong shape, letters where an integer belongs. */
    PARAM_TYPE,
    /** The right shape, outside the declared bounds. */
    PARAM_RANGE,
    /** An id the visitor has never seen in any response. The gate that stops a
     *  hallucinated product ever reaching the platform. */
    PARAM_UNKNOWN_REF
}
