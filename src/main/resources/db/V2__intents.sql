-- SLICE 2 · what the model chose, and what the platform did about it.
--
-- Two columns here exist because slice 1 could not answer a question it should
-- have been able to answer, and both are about telling truth apart from
-- absence.

-- The model produced something outside the closed alphabet, and which of the
-- ways it can be outside. A single INVALID bucket would make "the model does
-- not know the format" and "the model asked for a variant that does not exist"
-- the same number, and those call for opposite fixes: one is a prompt problem,
-- the other is a grounding problem.
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS reject_reason TEXT;
ALTER TABLE decisions ADD CONSTRAINT decisions_reject_ck CHECK (
    reject_reason IS NULL OR reject_reason IN (
        'UNPARSEABLE',        -- no line began with a token from the alphabet
        'UNKNOWN_INTENT',     -- a token, but not one this platform declares
        'MISSING_PARAM',      -- a declared param the model did not supply
        'PARAM_EXTRA',        -- a param the capability does not declare
        'PARAM_TYPE',         -- the wrong shape, e.g. letters where an int goes
        'PARAM_RANGE',        -- the right shape, outside the declared bounds
        'PARAM_UNKNOWN_REF'   -- an id the visitor has never seen in a response
    ));

-- Slice 1 could not distinguish "this run used no model" from "the model failed
-- and the seeded answer stood in", because both wrote source = SEEDED. Those
-- are different experiments: a run that was 90% fallback tells you about the
-- model's reliability, not about its judgement, and reporting the two together
-- would overstate what the model actually decided.
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS fallback BOOLEAN NOT NULL DEFAULT FALSE;

-- What the model actually said, kept when it was rejected. Without it, a
-- rejection rate is a number with no way to act on it: you know 12% of output
-- was unusable and have no idea what it looked like.
ALTER TABLE decisions ADD COLUMN IF NOT EXISTS raw TEXT;

-- The response, so an outcome can be re-derived if the classifier changes.
ALTER TABLE platform_calls ADD COLUMN IF NOT EXISTS response TEXT;
-- Why the classifier said what it said, in its own words.
ALTER TABLE platform_calls ADD COLUMN IF NOT EXISTS reason TEXT;

-- THE SAME KEY MAY BE SENT ONCE, EVER.
--
-- The key is derived from (run, visitor, tick, step), so replaying a window
-- must create nothing new. Enforcing it here rather than trusting the
-- derivation means a bug that dropped the run id from the key, which would
-- still look perfectly plausible in the code, fails loudly the first time two
-- runs overlap instead of silently colliding two experiments.
CREATE UNIQUE INDEX IF NOT EXISTS platform_calls_one_per_key ON platform_calls (idempotency_key);
