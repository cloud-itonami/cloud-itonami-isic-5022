# Security Policy

This project handles inland-waterway port/logistics-scheduling and
safety-concern workflows for river barge and towboat/pushboat freight
operations. Treat vulnerabilities as potentially high impact even when
the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real vessel, crew, cargo or shipper data exposure
- authorization bypass
- InlandWaterwayFreightGovernor bypass
- audit-ledger tampering
- over-disclosure in safety-concern reports or exports
- tenant isolation failures

## Reporting

Use GitHub private vulnerability reporting when available for the repository.
If that is unavailable, contact the repository maintainers through the
cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on vessel/crew/cargo/shipper data, policy enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real vessel, crew, cargo and shipper data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for operators and service accounts.
- This actor never navigates a barge or towboat, never operates lock
  infrastructure, and never overrides a tow captain's or lockmaster's
  safety judgment -- any deployment integration that would let it do so
  is a security-relevant design defect, not a feature gap; report it as
  such.
