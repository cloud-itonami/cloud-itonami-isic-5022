# cloud-itonami-isic-5022

Open Business Blueprint for **ISIC Rev.5 5022**: inland freight water
transport -- river barges, canal cargo vessels and towboat/pushboat
operations.

This repository publishes an inland-waterway-freight
PORT/LOGISTICS SCHEDULING coordination actor -- cargo/manifest/voyage
record logging, dock/lock-scheduling and voyage scheduling coordination,
barge/tow-vessel-maintenance procurement coordination with registered
contractors, and cargo-safety/river-worthiness-concern flagging -- as an
OSS business that any qualified operator can fork, deploy, run, improve
and sell, so an independent inland-waterway logistics coordinator never
surrenders shipping operations data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **InlandBargeAdvisor ⊣
InlandWaterwayFreightGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:inland-waterway-freight-governor`,
is a distinct, independent build (no naming-collision precedent question
-- distinct from sibling ISIC 5012's `:maritime-freight-governor` and
sibling ISIC 5021's `FerryDispatchGovernor`).

> **Why an actor layer at all?** An LLM is great at drafting a shipment-
> record summary, a dock/lock-scheduling proposal, or a maintenance-order
> request -- but it has no license to actually finalize a vessel-
> seaworthiness clearance or a cargo-load-safety clearance, no authority
> to override a tow captain's or lockmaster's safety judgment, no way to
> independently confirm a vessel or a maintenance-order contractor is
> actually a registered/verified counterparty, and no notion of when a
> "flag this concern" op quietly turns into a claim to have already
> acted on it. Letting it act directly invites an unverified vessel's
> data entering the ledger, an unverified contractor receiving a
> maintenance order, or -- worst of all -- a fabricated claim to have
> cleared a barge as river-worthy or overridden a tow captain's safety
> call, exposing the port operator, the vessel and its crew to real
> liability. This project seals the InlandBargeAdvisor into a single
> node and wraps it with an independent **InlandWaterwayFreightGovernor**,
> a human **approval workflow**, and an immutable **audit ledger**.

## Scope: inland-waterway logistics scheduling only, never navigation, lock operation or safety-clearance authority

This actor is **inland-waterway port/logistics scheduling coordination
only**. It never performs or authorizes:

- directly navigating, dispatching, or rerouting a barge or towboat
- directly operating lock gates or other inland-waterway control
  infrastructure
- directly finalizing a vessel-seaworthiness clearance or a cargo-
  load-safety clearance
- overriding a tow captain's or lockmaster's safety judgment, or
  bypassing an inland waterway safety protocol

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a cargo-safety/
river-worthiness concern for a human (tow captain/lockmaster) to triage
is exactly this actor's job -- `:flag-safety-concern` is never excluded
by this check, only FINALIZING/overriding/directly-acting-on that
concern is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`inlandbargeops.governor`'s `effect-not-propose-violations` HARD check
and `inlandbargeops.phase`'s phase table, which never puts
`:flag-safety-concern` in any phase's `:auto` set). A human inland-
logistics coordinator (or, for a safety concern, the tow captain/
lockmaster) is always the one who actually acts on a flagged concern or
confirms a high-cost maintenance order. This actor never navigates a
barge or towboat, never operates lock infrastructure, and never
overrides a tow captain's or lockmaster's safety judgment.

## The core contract

```
vessel/carrier registration + inland-waterway logistics scheduling request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────────┐
   │ InlandBargeAdvisor    │ ─────────────▶ │ InlandWaterwayFreightGovernor    │  (independent system)
   │ (sealed)              │  + citations    │ vessel-unverified ·              │
   └───────────────────────┘                 │ contractor-unverified ·          │
          │                 commit ◀┼ effect-not-propose ·                   │
          │                         │ scope-excluded (barge-navigation/       │
    record + ledger        escalate ┼ lock-operation/safety-clearance         │
          │              (ALWAYS for│ finalization) · op-not-allowed          │
          │       :flag-safety-     │                                          │
          │       concern/high-cost │                                          │
          │       maintenance-order)└────────────────────────────────┘
          ▼
      human approval
```

**The InlandBargeAdvisor never commits a proposal the
InlandWaterwayFreightGovernor would reject, and a safety-concern flag or
a high-cost maintenance order never commits without a human sign-off.**
Hard violations (an unregistered/unverified vessel; an unregistered/
unverified maintenance-order contractor; a non-`:propose` effect;
content touching barge-navigation/lock-operation/safety-clearance
finalization; an op outside the closed allowlist) force **hold** and
*cannot* be approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: dockside cargo handling,
lock-adjacent yard operations) under human/robot floor operations gated
by port policy. This actor itself does not dispatch robot/hardware
actions, and it never navigates a barge or towboat, and never operates
lock infrastructure -- it is strictly the inland-waterway logistics-
scheduling coordination layer (shipment-record logging, dock/lock-
operation scheduling, maintenance-order coordination, safety-concern
flagging) any physical-dispatch layer could eventually feed proposals
into, always gated the same way by the independent
InlandWaterwayFreightGovernor.

## Features

- **Closed proposal-op allowlist**: `log-shipment-record`,
  `schedule-berth-operation`, `coordinate-maintenance-order`,
  `flag-safety-concern` (all `:effect :propose`). None of these ops
  navigate a barge/towboat, operate lock infrastructure, or finalize a
  vessel-seaworthiness/cargo-load-safety clearance.
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Vessel unverified** -- the target vessel/carrier's registration
     must exist AND be independently registered/verified in the store.
  2. **Contractor unverified** -- for `:coordinate-maintenance-order`
     only, the named maintenance contractor must exist AND be
     independently registered/verified -- a maintenance-supply-chain
     counterparty-verification gate.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** -- directly finalizing a vessel-seaworthiness
     clearance or a cargo-load-safety clearance, overriding a tow
     captain's/lockmaster's safety judgment, bypassing an inland
     waterway safety protocol, directly navigating/dispatching a barge
     or towboat, directly operating lock gates, and an op outside the
     closed allowlist are all permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-safety-concern` -- ALWAYS escalates, regardless of confidence
    or phase. A "flag a concern" op is never auto-commit eligible and
    never finalizes a safety-clearance decision itself -- it only
    surfaces the concern for a human (tow captain/lockmaster).
  - `:coordinate-maintenance-order` above a cost threshold -- a
    large-value procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: shipment-record logging only (approval-gated)
  - Phase 2: + dock/lock-scheduling, maintenance-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (safety concerns and high-cost maintenance orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/inlandbargeops/governor_test.clj` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/inlandbargeops/advisor_test.clj` -- advisor proposal shape and
  consistency
- `test/inlandbargeops/phase_test.clj` -- rollout phase logic
- `test/inlandbargeops/governor_contract_test.clj` -- full graph
  integration, audit trail
- `test/inlandbargeops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `inlandbargeops.store` -- SSoT (MemStore, String-keyed vessel/
  contractor directories, append-only ledger)
- `inlandbargeops.advisor` -- contained intelligence node (mock +
  real-LLM seam)
- `inlandbargeops.governor` -- independent compliance layer
- `inlandbargeops.phase` -- staged rollout (0→3)
- `inlandbargeops.operation` -- langgraph-clj StateGraph
- `inlandbargeops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`5022`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Cargo/manifest/voyage record logging (`:log-shipment-record`) | Real vessel-tracking/AIS/river-information-system integration |
| Dock/lock-scheduling and voyage scheduling coordination (`:schedule-berth-operation`) | Direct barge/towboat navigation, dispatch, rerouting, or lock-gate operation |
| Barge/tow-vessel-maintenance procurement coordination with a registered, verified contractor, HARD-gated on contractor verification and a double-actuation-free single-proposal shape (`:coordinate-maintenance-order`) | Real drydock/maintenance-management-system integration |
| Cargo-safety/river-worthiness-concern flagging, ALWAYS human-gated (`:flag-safety-concern`) | Directly finalizing any vessel-seaworthiness or cargo-load-safety clearance, or overriding a tow captain's/lockmaster's safety judgment -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Customs/regulatory filing integration -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a demurrage-
notice or a cargo-discrepancy-escalation check) as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship checks already establish.

## Maturity

`:implemented` -- `InlandBargeAdvisor` + `InlandWaterwayFreightGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet,
with its own distinct, independently-named governor.

## License

Code and implementation templates are AGPL-3.0-or-later.
