# Data handling and privacy

CardDemo models a card issuer, so its records contain the kinds of data that PCI DSS and
privacy laws protect. All sample data in `app/data` is synthetic. Treat any real data loaded
into an environment as regulated.

## Sensitive fields

| Category | Fields (copybook) |
|:--|:--|
| Card data (PCI) | Card number `CARD-NUM`, `XREF-CARD-NUM`, `TRAN-CARD-NUM`; CVV `CARD-CVV-CD` (CVACT02Y, CVACT03Y, CVTRA05Y) |
| Government ID | `CUST-SSN`, `CUST-GOVT-ISSUED-ID` (CVCUS01Y) |
| Personal data | Names, addresses, phone numbers, `CUST-DOB-YYYY-MM-DD`, `CUST-FICO-CREDIT-SCORE` (CVCUS01Y) |
| Credentials | `SEC-USR-PWD` (CSUSR01Y) |

## Rules for code changes

- Do not DISPLAY sensitive fields, or records that contain them, to SYSOUT. Job logs are
  retained and readable by operators. Lint rule CD070 enforces this; existing violations in
  the baseline should be masked when the program is next changed.
- When a message must identify a card, show only the last four digits, for example by moving
  the number to a masked work field (`************1234`) before the DISPLAY.
- Do not add sensitive fields to new report or extract files unless the consumer needs them.
  CBEXPORT includes customer data by design (it feeds a migration target); protect its output
  dataset with RACF and encrypt it in transit.
- Never commit real customer data, production extracts, or credentials. Gitleaks runs in CI
  and in the pre-commit hook.

## Credentials and secrets

- `USRSEC` stores demo passwords in clear text, and the README documents the demo password.
  This is acceptable only for the sample; a real deployment must use RACF (or an external
  identity provider) for sign-on rather than USRSEC passwords.
- Mainframe host names, user IDs, and passwords for the `scripts/` helpers belong in
  `~/.netrc` or your secret manager, never in the repository or `.env`. `.env` is git-ignored
  and `.env.example` contains no secrets.
- CI needs no secrets. If you add a workflow that does, use GitHub encrypted secrets or OIDC.

## Logs

Batch job logs and CICS transient data queues are the only logs. Before attaching a job log
to an issue or sharing it outside the team, mask card numbers, SSNs, and names. Prefer the
synthetic sample data when reproducing problems.

## Retention and deletion

The sample keeps no retention schedule. A real deployment must define retention for
TRANSACT backups (GDG limit 5 in DEFGDGB), statements, and DALYREJS, and support deleting a
customer's records across CUSTDATA, CARDXREF, CARDDATA, ACCTDATA, and TRANSACT.
