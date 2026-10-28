-- Batch cycle state (W2): INTCALC month tracking, CREASTMT statement runs, pre-posting backups.

-- INTCALC (Q-CBACT04C-08, B-UOW): one row per account whose interest for a calendar month was committed
-- (its interest rows and its account update commit in the same database transaction as this row).
-- A rerun after an abended run in the same month skips these accounts.
CREATE TABLE interest_account (
    interest_month  CHAR(7)       NOT NULL,   -- YYYY-MM of the business date
    account_id      BIGINT        NOT NULL,
    business_date   DATE          NOT NULL,
    interest_rows   INT           NOT NULL,
    total_interest  DECIMAL(11,2) NOT NULL,
    processed_at    DATETIME2(3)  NOT NULL,
    CONSTRAINT pk_interest_account PRIMARY KEY (interest_month, account_id)
);

-- CREASTMT (Q-CREASTMT-04, D-HISTORY): each run covers the transaction rows committed before it started
-- that no earlier run covered; card_transaction.statement_run_id points here.
CREATE TABLE statement_run (
    statement_run_id   BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    business_date      DATE          NOT NULL,
    started_at         DATETIME2(3)  NOT NULL,
    transaction_count  INT           NOT NULL,
    statement_count    INT           NOT NULL
);

-- BACKUP / RESTORE (Q-TRANBKP-01, Q-ACCTFILE-02, B-CYCLE): a consistent snapshot of the tables the cycle
-- changes, taken before POSTTRAN. RESTORE puts them back and forgets the batch runs started after it.
CREATE TABLE cycle_backup (
    backup_id             BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    business_date         DATE          NOT NULL,
    created_at            DATETIME2(3)  NOT NULL,
    max_reject_id         BIGINT        NOT NULL,
    max_statement_run_id  BIGINT        NOT NULL,
    max_job_instance_id   BIGINT        NOT NULL,
    account_rows          INT           NOT NULL,
    transaction_rows      INT           NOT NULL,
    category_rows         INT           NOT NULL,
    restored_at           DATETIME2(3)  NULL
);

CREATE TABLE cycle_backup_account (
    backup_id           BIGINT        NOT NULL REFERENCES cycle_backup (backup_id) ON DELETE CASCADE,
    account_id          BIGINT        NOT NULL,
    active_status       VARCHAR(1)    NOT NULL,
    current_balance     DECIMAL(12,2) NOT NULL,
    credit_limit        DECIMAL(12,2) NOT NULL,
    cash_credit_limit   DECIMAL(12,2) NOT NULL,
    open_date           DATE          NULL,
    expiration_date     DATE          NULL,
    reissue_date        DATE          NULL,
    curr_cycle_credit   DECIMAL(12,2) NOT NULL,
    curr_cycle_debit    DECIMAL(12,2) NOT NULL,
    addr_zip            VARCHAR(10)   NOT NULL,
    group_id            VARCHAR(10)   COLLATE Latin1_General_BIN2 NOT NULL,
    CONSTRAINT pk_cycle_backup_account PRIMARY KEY (backup_id, account_id)
);

-- Transactions are only ever added or marked by a statement run, so the snapshot keeps their keys and marks.
CREATE TABLE cycle_backup_transaction (
    backup_id         BIGINT   NOT NULL REFERENCES cycle_backup (backup_id) ON DELETE CASCADE,
    tran_id           CHAR(16) COLLATE Latin1_General_BIN2 NOT NULL,
    statement_run_id  BIGINT   NULL,
    CONSTRAINT pk_cycle_backup_transaction PRIMARY KEY (backup_id, tran_id)
);

CREATE TABLE cycle_backup_category (
    backup_id      BIGINT        NOT NULL REFERENCES cycle_backup (backup_id) ON DELETE CASCADE,
    account_id     BIGINT        NOT NULL,
    type_cd        VARCHAR(2)    COLLATE Latin1_General_BIN2 NOT NULL,
    cat_cd         SMALLINT      NOT NULL,
    balance        DECIMAL(11,2) NOT NULL,
    legacy_filler  VARCHAR(22)   NOT NULL,
    CONSTRAINT pk_cycle_backup_category PRIMARY KEY (backup_id, account_id, type_cd, cat_cd)
);

CREATE TABLE cycle_backup_interest (
    backup_id       BIGINT        NOT NULL REFERENCES cycle_backup (backup_id) ON DELETE CASCADE,
    interest_month  CHAR(7)       NOT NULL,
    account_id      BIGINT        NOT NULL,
    business_date   DATE          NOT NULL,
    interest_rows   INT           NOT NULL,
    total_interest  DECIMAL(11,2) NOT NULL,
    processed_at    DATETIME2(3)  NOT NULL,
    CONSTRAINT pk_cycle_backup_interest PRIMARY KEY (backup_id, interest_month, account_id)
);
