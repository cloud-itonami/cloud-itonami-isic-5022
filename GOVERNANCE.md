# Governance

`cloud-itonami-isic-5022` is an OSS open-business blueprint for
inland-waterway-freight port/logistics scheduling coordination (ISIC
Rev.5 5022 -- inland freight water transport).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered vessel, or a maintenance
  order naming an unverified/unregistered contractor, can never commit.
- the InlandWaterwayFreightGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, barge-navigation/
  lock-operation/vessel-seaworthiness-clearance/cargo-load-safety-
  clearance-finalization content, an op outside the closed allowlist)
  cannot be overridden by human approval.
- this actor never directly navigates, dispatches or reroutes a barge
  or towboat, never directly operates lock gates or other inland-
  waterway control infrastructure, and never overrides a tow captain's
  or lockmaster's safety judgment.
- every shipment-record log, dock/lock-operation schedule, maintenance-
  order coordination and safety-concern flag is auditable.
- vessel, crew, cargo and shipper data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review.

Certified operators can lose certification for:
- bypassing shipment-record, dock/lock-scheduling, maintenance-order or
  safety-concern policy checks
- mishandling vessel, crew, cargo or shipper data
- misrepresenting certification status
- failing to respond to security or safety incidents
