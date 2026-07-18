-- AGENTIC VISITORS · its own database, because it owns a domain nothing else
-- owns: the visitor's identity, what it intended, and what producing that
-- intent COST.
--
-- The central idea of this schema is that the budget is a LEDGER, not a
-- counter. A language model is a metered external dependency, and "do not
-- overspend" is structurally the same sentence as minimart's "do not oversell"
-- and minibank's "do not overdraw": a non-negative CHECK on a balance, taken
-- under an ordered row lock. A counter in application code is a promise
-- somebody has to remember to keep. This is a constraint the database enforces
-- whatever the agent loop does, including looping forever.

-- ---------------------------------------------------------------- the ledger

CREATE TABLE IF NOT EXISTS accounts (
    id       BIGSERIAL PRIMARY KEY,
    ref      TEXT NOT NULL UNIQUE,
    -- 'external' is where value comes FROM and is allowed to go negative: its
    -- balance is the mirror of everything ever handed out. Every other account
    -- is floored at zero, and that floor IS the overspend guarantee.
    kind     TEXT NOT NULL,
    -- MICRO_EUR for money. TOKEN:{model} for tokens. The same trick minimart
    -- plays with UNIT:{variantId}: one ledger, several denominations, and a
    -- transaction must sum to zero in each of them independently.
    currency TEXT NOT NULL,
    balance  NUMERIC(30,6) NOT NULL DEFAULT 0,
    CONSTRAINT accounts_kind_ck CHECK (kind IN ('external','internal')),
    CONSTRAINT accounts_non_negative CHECK (kind = 'external' OR balance >= 0)
);

CREATE TABLE IF NOT EXISTS transactions (
    id          UUID PRIMARY KEY,          -- caller-minted, so a retry is a no-op
    kind        TEXT        NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS entries (
    id          BIGSERIAL PRIMARY KEY,
    tx_id       UUID NOT NULL REFERENCES transactions(id),
    account_id  BIGINT NOT NULL REFERENCES accounts(id),
    amount      NUMERIC(30,6) NOT NULL,
    business_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS entries_by_account ON entries (account_id);
CREATE INDEX IF NOT EXISTS entries_by_tx ON entries (tx_id);

-- Append-only, enforced by the database rather than by everyone remembering.
-- An audit trail that can be edited is not an audit trail.
CREATE OR REPLACE FUNCTION entries_are_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'entries are append only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS entries_no_update ON entries;
CREATE TRIGGER entries_no_update BEFORE UPDATE OR DELETE ON entries
    FOR EACH ROW EXECUTE FUNCTION entries_are_immutable();

-- ----------------------------------------------------------------- holds
--
-- A model call costs what it costs only AFTER it returns, because output
-- tokens are not known in advance. So spending is authorize/capture/void, the
-- same lifecycle minibank uses for a card and minimart uses for a reservation,
-- and for exactly the same reason: commit to an upper bound first, settle the
-- real amount second, and release the difference either way.
CREATE TABLE IF NOT EXISTS holds (
    id          UUID PRIMARY KEY,
    run_id      UUID        NOT NULL,
    -- the ceiling authorised before the call, derived from max_tokens
    authorized  NUMERIC(30,6) NOT NULL,
    captured    NUMERIC(30,6),
    -- The estimate was too low and the call really cost more. Recorded rather
    -- than refused: the money was genuinely spent, and a ledger that declines
    -- to write down a real cost is worse than one that admits its estimator
    -- was wrong. Refusing here was the first version's bug, and it made real
    -- calls look free.
    overage     NUMERIC(30,6) NOT NULL DEFAULT 0,
    state       TEXT        NOT NULL,
    business_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT holds_state_ck CHECK (state IN ('AUTHORIZED','CAPTURED','VOIDED'))
);
CREATE INDEX IF NOT EXISTS holds_by_run ON holds (run_id);

-- ------------------------------------------------------------------- runs

CREATE TABLE IF NOT EXISTS runs (
    id              UUID PRIMARY KEY,
    -- LIVE   · calls the model, records cassettes, needs a funded account
    -- REPLAY · cassettes only. A miss is fatal and never becomes a live call.
    -- SEEDED · no model at all, decisions from a pure function. CI runs here.
    mode            TEXT        NOT NULL,
    -- Everything that makes two runs comparable, in one string. Comparing runs
    -- with different fingerprints is comparing different experiments, and it is
    -- refused at the query layer rather than left to whoever reads the chart.
    arm_fingerprint TEXT        NOT NULL,
    model_id        TEXT        NOT NULL,
    model_version   TEXT        NOT NULL,
    template_id     TEXT        NOT NULL,
    -- A run that stopped early makes every number downstream of it
    -- survivorship-biased, so why it stopped is not optional.
    ended_reason    TEXT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    CONSTRAINT runs_mode_ck CHECK (mode IN ('LIVE','REPLAY','SEEDED')),
    CONSTRAINT runs_ended_ck CHECK (ended_reason IS NULL OR ended_reason IN
        ('ticks_complete','budget_exhausted','error_budget_exceeded','deadline','aborted'))
);

-- ---------------------------------------------------------------- cassettes

-- A recorded model response, keyed by CONTENT and never by call order.
--
-- Ordinal keying ("the fourth call in this test") breaks the moment anything
-- runs concurrently, and it breaks intermittently, which is the worst way for
-- test infrastructure to fail. The key here is a hash of everything that could
-- change the answer, so concurrency is irrelevant and a changed prompt is a
-- detectable conflict rather than a silent difference.
CREATE TABLE IF NOT EXISTS cassettes (
    fingerprint   TEXT PRIMARY KEY,
    model_id      TEXT        NOT NULL,
    model_version TEXT        NOT NULL,
    template_id   TEXT        NOT NULL,
    prompt        TEXT        NOT NULL,
    response      TEXT        NOT NULL,
    prompt_tokens INT         NOT NULL DEFAULT 0,
    output_tokens INT         NOT NULL DEFAULT 0,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- decisions

CREATE TABLE IF NOT EXISTS decisions (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID        NOT NULL REFERENCES runs(id),
    visitor_id   INT         NOT NULL,
    tick         INT         NOT NULL,
    step         INT         NOT NULL,
    -- the closed alphabet. The model does not emit actions, it emits an INTENT
    -- which is validated against this list before anything touches a platform.
    intent       TEXT        NOT NULL,
    params       TEXT        NOT NULL DEFAULT '{}',
    -- MODEL   · a language model chose it
    -- SEEDED  · the pure fallback chose it
    -- INVALID · the model emitted something outside the alphabet
    source       TEXT        NOT NULL,
    fingerprint  TEXT,
    micro_eur    NUMERIC(30,6) NOT NULL DEFAULT 0,
    latency_ms   INT         NOT NULL DEFAULT 0,
    business_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT decisions_source_ck CHECK (source IN ('MODEL','SEEDED','INVALID','CASSETTE')),
    -- one decision per (run, visitor, tick, step), so a replayed window
    -- produces the same decisions instead of a second set of them
    CONSTRAINT decisions_once UNIQUE (run_id, visitor_id, tick, step)
);
CREATE INDEX IF NOT EXISTS decisions_by_run ON decisions (run_id);

-- ------------------------------------------------------------ platform calls

-- Every intent that was actually sent somewhere, with the idempotency key it
-- carried. The reconciliation audit reads this and proves every intent either
-- landed or was refused with a recorded reason: no silently dropped actions.
CREATE TABLE IF NOT EXISTS platform_calls (
    id              BIGSERIAL PRIMARY KEY,
    run_id          UUID        NOT NULL REFERENCES runs(id),
    decision_id     BIGINT      NOT NULL REFERENCES decisions(id),
    platform        TEXT        NOT NULL,
    action          TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    status          INT,
    outcome         TEXT        NOT NULL,
    business_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT platform_calls_outcome_ck CHECK (outcome IN ('LANDED','REFUSED','ERROR','PENDING'))
);
CREATE INDEX IF NOT EXISTS platform_calls_by_run ON platform_calls (run_id);
