# Business Model: Inland-Waterway-Freight Port/Logistics Scheduling Coordination

## Classification
- Repository: `cloud-itonami-isic-5022`
- ISIC Rev.5: `5022` -- inland freight water transport (river barges,
  canal cargo vessels, towboat/pushboat operations)
- Social impact: safety, supply-chain resilience, transparency

## Customer
- independent inland-waterway port operators and freight-forwarding
  coordinators needing an auditable operations-coordination platform
- multi-barge/multi-dock operators needing consistent maintenance/
  scheduling/safety governance across a fleet
- programs that cannot accept closed, unauditable back-office platforms
  for cargo and voyage records

## Offer
- cargo/manifest/voyage record logging
- dock/lock-scheduling and voyage scheduling coordination
- barge/tow-vessel-maintenance procurement coordination with
  registered, verified contractors
- cargo-safety/river-worthiness-concern flagging (hazmat placarding,
  load-securement anomalies, hull/draft/trim observations) for human
  (tow captain/lockmaster) triage
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per port/vessel
- support retainer with SLA

## Trust Controls
- `:inland-waterway-freight-governor` never lets a proposal for an
  unregistered/unverified vessel, or a maintenance order naming an
  unregistered/unverified contractor, commit or even escalate
- every proposal's `:effect` must be `:propose` -- a claim to directly
  actuate is a HARD, un-overridable block
- directly finalizing a vessel-seaworthiness clearance or a cargo-load-
  safety clearance, overriding a tow captain's/lockmaster's safety
  judgment, bypassing an inland waterway safety protocol, or directly
  navigating/dispatching a barge or towboat or operating lock gates is
  permanently out of scope, not a rollout milestone -- the actor may
  only flag a concern for a human
- a `:flag-safety-concern` proposal, and a high-cost `:coordinate-
  maintenance-order`, always require human sign-off
- sensitive vessel, crew, cargo and shipper data stays outside Git
