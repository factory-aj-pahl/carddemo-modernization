# Owed data files (data-roundtrip)

Every sample data file in the repository, with what the data migration owes for it. `verify_data.py`
(`make -C modernization verify-data`) reads the table below before anything runs: every file under
`app/data/ASCII` and `app/data/EBCDIC` must have a row, every `owed` file must exist, load, and export
back, and a row that cannot be checked is a failure. `pending` rows belong to the module workers and
are listed in the report without being checked; `not-owed` rows give the reason.

## Round-trip rule

For each owed fixed-width file: a fresh schema (`carddemo-batch reset`, Flyway clean and migrate) is
loaded with `carddemo-batch load DIR usrsec=FILE`, the row count of the target table must equal the
record count of the file, and `carddemo-batch export DIR` must reproduce the file byte for byte after
only two normalizations of the source: line terminators (CRLF and LF both become LF) and right-padding
of short lines with spaces to the record length (`cardxref.txt` lines are 36 bytes, entities.md DF-09).

The one documented content change is the Q-ENT-02 migration rule (RP-DATA-1,
`decisions/ledger-batch-data.md`): in `acctdata.txt`, when bytes 113-122 are blank and bytes 103-112
hold a group ID present in `discgrp.txt`, the value moves to bytes 113-122 and bytes 103-112 become
blank. The expected export of `acctdata.txt` is the source with that rule applied (50 of 50 sample
records change); every other byte is compared unchanged.

Signed zoned (overpunch) values are also checked in the database, not only through the round trip
(entities.md DF-02, DF-03): `0000005047G` = +504.77, `0000009190}` = -919.00 and `0000000567P` = -56.77
(`dailytran.txt` TRAN-AMT), `00000001940{` = 194.00 (`acctdata.txt` account 1 current balance), and
`00150{` = 15.00 (`discgrp.txt` interest rate, DIS-INT-RATE S9(04)V99; entities.md said 1.50 and was
corrected).

### DALYTRAN: input file, not a table

`dailytran.txt` (ENT-DAILY-TRANSACTION, CVTRA06Y) stays a job input file: POSTTRAN reads the feed
directly (`dailyFile=PATH`) and keeps no staging table, so a posting run sees exactly the bytes of the
delivered file. The spec's proposed staging table is not built. The record layout is byte-identical to
ENT-TRANSACTION (CVTRA05Y), so its round trip is checked through the `card_transaction` table and the
TRANSACT layout: the file is staged as `transact.txt`, loaded (300 rows), exported, and compared with
`dailytran.txt`. This is the same codec that loads TRANSACT for the online scenarios whose spec
assumption is "TRANSACT holds the 300 records of dailytran.txt".

### USRSEC: what is compared

`AWS.M2.CARDDEMO.USRSEC.PS` is EBCDIC (code page 037), 10 records of 80 bytes. Passwords are stored
as BCrypt hashes (D-AUTH, Q-COSGN00C-01), so the clear-text password bytes 49-56 cannot come back. The
check rebuilds each 80-byte record from `app_user` (user ID bytes 1-8, first name 9-28, last name
29-48, user type 57, FILLER 58-80 as spaces) and compares every byte except 49-56 with the decoded
source record; for bytes 49-56 it checks that the stored value is a BCrypt hash (`$2a$`, 60 characters)
and not the clear text. That each hash verifies against the source password is checked by the Java
test `DataLoadRoundTripTest` (batch module). Row count: 10 users; 50 account links (D-SEED).

## Files

| File | Records | Record length | Table | Status | Owner | Notes |
|:--|--:|--:|:--|:--|:--|:--|
| `app/data/ASCII/acctdata.txt` | 50 | 300 | `account` | owed | W1 (P-DATA) | LF; Q-ENT-02 group-ID move applied to the expected export |
| `app/data/ASCII/carddata.txt` | 50 | 150 | `card` | owed | W1 (P-DATA) | LF |
| `app/data/ASCII/cardxref.txt` | 50 | 50 | `card_xref` | owed | W1 (P-DATA) | LF, lines 36 bytes (right-padded) |
| `app/data/ASCII/custdata.txt` | 50 | 500 | `customer` | owed | W1 (P-DATA) | LF; FILLER bytes 335-500 are spaces in every record |
| `app/data/ASCII/dailytran.txt` | 300 | 350 | `card_transaction` | owed | W1 (P-DATA) | LF; job input file, round trip through the TRANSACT layout (see above) |
| `app/data/ASCII/discgrp.txt` | 51 | 50 | `disclosure_group` | owed | W1 (P-DATA) | LF; FILLER zeros kept in `legacy_filler` |
| `app/data/ASCII/tcatbal.txt` | 50 | 50 | `tran_cat_balance` | owed | W1 (P-DATA) | mixed CRLF and LF; FILLER zeros kept in `legacy_filler` |
| `app/data/ASCII/trancatg.txt` | 18 | 60 | `transaction_category` | owed | W1 (P-DATA) | CRLF; FILLER zeros kept in `legacy_filler` |
| `app/data/ASCII/trantype.txt` | 7 | 60 | `transaction_type` | owed | W1 (P-DATA) | mixed CRLF and LF; FILLER zeros kept in `legacy_filler` |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS` | 10 | 80 | `app_user` | owed | W1 (P-DATA) | EBCDIC cp037; compared as described above (password bytes as BCrypt) |
| `app/app-transaction-type-db2/ctl/DB2LTTYP.ctl` | 7 | n/a | `transaction_type` | pending | module worker (transaction-type Db2) | Db2 LOAD seed; DF-1 seeds types from `trantype.txt`, the module decides how this seed is reconciled |
| `app/app-transaction-type-db2/ctl/DB2LTCAT.ctl` | 18 | n/a | `transaction_category` | pending | module worker (transaction-type Db2) | Db2 LOAD seed (with `app/app-transaction-type-db2/ddl/*.ddl`) |
| `app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat` | n/a | n/a | pending authorization tables | pending | module worker (authorization IMS/Db2/MQ) | IMS unload sample, 51,736 bytes |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS` | 50 | 300 | n/a | not-owed | n/a | EBCDIC twin of `acctdata.txt`; the ASCII set is the migration baseline (DF-2) |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.ACCDATA.PS` | 50 | 300 | n/a | not-owed | n/a | Duplicate of `ACCTDATA.PS` (entities.md sample findings); DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.CARDDATA.PS` | 50 | 150 | n/a | not-owed | n/a | EBCDIC twin of `carddata.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS` | 50 | 50 | n/a | not-owed | n/a | EBCDIC twin of `cardxref.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.CUSTDATA.PS` | 50 | 500 | n/a | not-owed | n/a | EBCDIC twin of `custdata.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS` | 300 | 350 | n/a | not-owed | n/a | EBCDIC twin of `dailytran.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` | 1 | 350 | n/a | not-owed | n/a | One record of LOW-VALUES used to initialise TRANSACT; not migrated (Q-ENT-31) |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS` | 51 | 50 | n/a | not-owed | n/a | EBCDIC twin of `discgrp.txt` (differs in one rate, Q-ENT-13); DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.TCATBALF.PS` | 50 | 50 | n/a | not-owed | n/a | EBCDIC twin of `tcatbal.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.TRANCATG.PS` | 18 | 60 | n/a | not-owed | n/a | EBCDIC twin of `trancatg.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS` | 7 | 60 | n/a | not-owed | n/a | EBCDIC twin of `trantype.txt`; DF-2 |
| `app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS` | 500 | 500 | n/a | not-owed | n/a | Not owed by P-DATA: it is an interchange file, the CBIMPORT input, covered by the CBIMPORT parity pairs (`verification/parity/datasets.md`) |
