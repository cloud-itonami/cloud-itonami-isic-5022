# Operator Guide

## First Deployment
1. Register operator, vessels (barges/towboats) and maintenance
   contractors; independently confirm each vessel's registration/
   carrier license and each contractor's registration before seeding
   `inlandbargeops.store`.
2. Import existing cargo/manifest/voyage, dock/lock-scheduling and
   maintenance-order history.
3. Run read-only shipment-record-logging and dock/lock-scheduling
   dry-runs (Phase 0-1).
4. Configure the rollout phase and the `coordinate-maintenance-order`
   cost-escalation threshold for human sign-off paths.
5. Publish a dry-run safety-concern flag and audit export.

## Minimum Production Controls
- vessel-registration/verification check before ANY proposal for that
  vessel
- contractor-registration/verification check before ANY `:coordinate-
  maintenance-order` proposal
- governor gate on every proposal before commit
- human sign-off for `:flag-safety-concern` (always) and high-cost
  `:coordinate-maintenance-order` proposals
- audit export for every commit, hold and approval
- backup manual back-office process
- this actor MUST NOT be wired to any barge/towboat-navigation,
  dispatch, lock-gate-operation, seaworthiness-clearance or cargo-load-
  safety-clearance system as a finalizing authority -- it may only feed
  proposals to a human tow captain/lockmaster

## Certification
Certified operators must prove vessel/contractor-verification
discipline, governor-bypass resistance, evidence-backed safety-concern
reporting and human (tow captain/lockmaster) review for every
escalation-gated action.
