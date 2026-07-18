# agentic-visitors

**A containment shell for a non-deterministic, priced, unreliable external dependency,** which happens to be a
language model, and which happens to be driving customer behaviour. Raw Java 21, no frameworks.

## Why this is not "a thing that calls an LLM"

[minimart](https://github.com/igorfyago/minimart) already has simulated customers. They are seeded pure
functions: every decision is derived from `(runId, agentId, tick, step)`, which makes a run perfectly
reproducible and makes the population an honest load and logic oracle. Bolting a model onto that would be a
wrapper, not a step up.

The step up is that a language model is a **metered, slow, occasionally-wrong third-party dependency**, and
containing one is a real engineering problem with a real shape. Every rule in this repository would still apply
if the dependency were a sanctions-screening vendor, an FX-rate provider or a fraud-scoring API. That is the test
of whether the framing is honest, and it is the reason the first slice contains no model at all.

## The spend is a ledger, not a counter

The usual answer to "stop the agent loop spending too much" is a counter in application code, or a cap in the
vendor's dashboard. Both are the wrong shape: a counter is a promise somebody has to remember to keep, and a
dashboard cap is invisible to the system, fails the run in an uncontrolled place, and cannot tell you what an
experiment cost.

So a run is **funded** before it starts, every call **debits** it, and every non-treasury balance carries a
non-negative CHECK under an ordered row lock.

> **"Cannot overspend" is the same sentence as minimart's "cannot oversell" and minibank's "cannot overdraw".**

One constraint, enforced by the database, whatever the loop above it does, including looping forever.

And because the cost of a call is unknowable until it returns, spending is **authorize, capture, void**: hold an
upper bound derived from `max_tokens`, settle the real amount when the response arrives, release the difference.
That is the third time the card-hold lifecycle has turned out to be the right mechanism, after minibank's
authorisations and minimart's stock reservations.

## Reproducibility, when the core is not reproducible

Three layers, and the third is the one that is usually missed.

**Cassettes, keyed by content.** Every response is recorded under a hash of the model, its pinned version, the
template, the exact rendered prompt bytes and the sampling parameters. Not by call order: ordinal keying breaks
the moment anything runs concurrently, and it breaks intermittently, which is the worst way for test
infrastructure to fail.

**Three modes.** `LIVE` records. `REPLAY` replays and **may never call the model**, so a miss is fatal. `SEEDED`
involves no model at all and is what CI runs, which means CI cannot spend money by construction rather than by
policy.

**Reproducible effects, not reproducible prose.** The model does not emit actions. It emits an **intent** from a
closed alphabet, validated before anything touches a platform, and the platform-facing idempotency key stays
derived from `(runId, visitorId, tick, step)` exactly as it is in minimart. So the wording may vary while the set
of side effects stays gated by derived ids, and replaying a window still creates nothing new.

**What is not claimed:** that `temperature=0` gives determinism. It does not. Batched inference and
non-deterministic kernels break it, and a vendor can change the weights behind a stable name. The version is
pinned, recorded, and part of the cassette key, so a model change registers as a conflict instead of quietly
becoming a different experiment with the same name.

## What it will honestly prove

**An open-world input generator and a legibility harness.** It exercises action sequences its author never
enumerated, which is genuinely more than a seeded population can do, since that one can only walk paths somebody
typed. And it measures whether a competent reasoner, given the public API and nothing else, can accomplish a
stated goal, which turns "is this API well designed" from an assertion into a number.

**It is not a forecast and it is not a user study.** A model roleplaying a price-sensitive shopper produces a
plausible narration, not a draw from a real population. Behaviour that really happened is `MEASURED`; a claim
that a real person would have behaved that way is `AUTHORED`, always, and the two never mix.

## Slice 1 · the shell, with no model in sight (done, 6/6 lessons green)

Built and proven against a fake model, for zero euros. If the shell is right the real model is a plug; if it is
wrong, learning that with an invoice attached would be an expensive way to find out.

- *a recorded decision replays byte-identically* and **the model is not called again**. The second assertion is
  the one that matters: returning the right text while secretly re-calling would pass a naive test, cost money
  every run, and quietly destroy reproducibility.
- *a prompt that changed is a conflict*, not a silent re-run, and a different model version is a different key.
- *in REPLAY, a miss is fatal and never escalates to a live call.* The most important rule here. Test
  infrastructure fails by silently degrading into non-determinism, and this is where that would begin.
- *fifty concurrent visitors against a budget for ten* → exactly ten calls, forty refused **before** the model
  was reached, balance never negative, every transaction still summing to zero.
- *the model is down* → the visitor still decides, the fallback is recorded as a fallback rather than passed off
  as the model, and the hold is voided in full.

**A bug the concurrency lesson found, in code written the obvious way.** The first version wrapped the call, the
capture and the recording in one `try`, with a catch that voided the hold and fell back. So when the *capture*
threw, on a constraint refusing to record a cost higher than the estimate, the system concluded the *model* had
failed: it voided the hold and fifty calls that genuinely happened were recorded as free. The budget looked
untouched. Lesson 4 failed with 50 allowed instead of 10, which is the only reason anyone found out.

Two rules came out of it, and lesson 6 now pins them: a response that arrived is billed whatever goes wrong
afterwards, because the vendor has already been paid; and an estimate that proves too low is **recorded as an
overage rather than refused**, because a ledger that declines to write down a real cost makes real spending look
free, which is the one direction in which nobody notices.

## Slice 2 · the seam, and one real task (done, 16/16 lessons green)

A platform is a FILE. `src/main/resources/platforms/minimart.json` holds every fact about minimart: its
endpoints, its closed intent alphabet, which params the model may choose and which the runtime owns, what
counts as success, and the identity range its visitors occupy. No Java file in this repository contains any of
it, and a lesson proves that by reading the source.

**The claim, and how it is falsified.** "Adding a platform is a manifest file and zero core changes" is easy to
say and easy to fake. The usual fake is that the manifest names the endpoints while the core still knows
success means 200. So one lesson boots a **lending library** with deliberately incompatible conventions, 201
for a created loan, errors under `problem` rather than `error`, and a 403 that genuinely means refused, and
drives it with nothing but a manifest written inside the test. If any convention had leaked, a created loan
would classify as an error and it fails.

The second lesson catches what the first cannot: a convenience constant in the core that happens to agree with
the manifest, and only disagrees in production. It scans every source file under `dev/visitors` for platform
vocabulary, with no carve-out. **It found one immediately**, in the database configuration, which had a
neighbouring service's name in its connection defaults. Credentials and addresses now come from the
environment, and a local properties file, and neutral defaults.

**The model proposes, it does not act.** Output is parsed into the alphabet the manifest declares, every
parameter is checked against a declared domain, and only then does a value exist that could be executed:
`Intent` is constructible only by the grammar, so an unvalidated intent has no representation in the type
system. The rejection reasons are a taxonomy rather than one INVALID bucket, because they call for opposite
fixes and one number would hide which is needed.

- *a model that invents a product* → `PARAM_UNKNOWN_REF`, and no request is ever sent. A visitor may only name
  ids it has actually SEEN in a response, which is where "it sees HTTP responses and nothing else" stops being
  a principle and becomes a gate.
- *a model that sets `business_at` or its own `customer`* → `PARAM_EXTRA`. One could move business time, the
  other could act as somebody else, and both would be invisible in the results.
- *`interval_days=thirty` versus `interval_days=3650`* → `PARAM_TYPE` and `PARAM_RANGE`. One is a model that
  cannot format, the other is a model that cannot judge, and the fixes have nothing in common.
- *a model that narrates and then acts* → accepted. Rejecting it would report an invalid rate that is really a
  measure of the parser's strictness.

**What is written down, and when.** The intention is committed BEFORE the socket opens, and a lesson proves it
by reading the row from a separate connection while the request is still in flight. The obvious implementation,
send then record, passes every other test and leaves a crash-shaped hole: an effect out in the world that this
system has no record of causing.

A lost answer is **PENDING**, not ERROR. A read timeout means the request may well have been received, applied
and committed while the response was lost coming back, and recording that as a failure asserts knowledge nobody
has. The lesson has the platform genuinely apply the request and simply never answer in time.

**And it does the task, against the real thing.** One lesson drives a running minimart over a socket: browse,
subscribe to something it learned from the response, ask whether it is really subscribed, cancel, and then
grade the result by ASKING THE PLATFORM. A visitor that issues a cancel and a platform that ignores it look
identical from the client side, and only one of them is success. It skips rather than fails when minimart is
not running, because a red suite for want of a neighbouring service teaches people to ignore red suites.

**A bug that only appeared the second time the suite was ever run.** Identity was derived from the visitor
number alone, so visitor 7 was the same customer in every run, and run two opened by finding run one's
cancelled subscription still sitting there. A run that inherits its own past is not measuring what it thinks it
is. Identity now carries the run as well, and a lesson runs the experiment twice on purpose, because anything
that only shows up on a second run deserves a test that does one.

### Next

- **Slice 3** · one visitor identity that banks at minibank and shops at minimart, plus the reconciliation
  audit across two ledgers that share no database.
- **The task loop and the model in it.** Slice 2 executes intents; the tick loop, the seeded policy and the
  prompt renderer are what turn that into a run, and the renderer's determinism is what the cassette hit rate
  rests on.
- **An A/A run, published.** The identical arm twice, with the spread in outcomes shown, and the rule that no
  effect smaller than that spread gets reported.
