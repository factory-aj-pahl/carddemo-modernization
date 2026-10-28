-- Core schema for the base CardDemo entities (spec/entities.md).
-- Replaces the IDCAMS DEFINE jobs (ACCTFILE, CARDFILE, CUSTFILE, XREFFILE, TRANFILE, TCATBALF,
-- TRANTYPE, TRANCATG, DISCGRP, DUSRSECJ). Character keys use a binary collation so browse order
-- matches the byte order of the reference runs (entities.md, encoding finding 7).
-- Text columns hold the COBOL field content with trailing spaces removed; exporters pad them back.
-- legacy_filler keeps non-blank FILLER bytes of loaded records (the sample reference files carry zeros
-- there) so an export reproduces the source byte for byte; rows created by the target leave it blank.

-- BC-1 IAM ---------------------------------------------------------------------------------
CREATE TABLE app_user (
    user_id          VARCHAR(8)  COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    first_name       VARCHAR(20) NOT NULL DEFAULT '',
    last_name        VARCHAR(20) NOT NULL DEFAULT '',
    password_hash    VARCHAR(100) NOT NULL,
    user_type        CHAR(1)     NOT NULL CONSTRAINT ck_app_user_type CHECK (user_type IN ('A', 'U')),
    failed_attempts  INT         NOT NULL DEFAULT 0,
    locked_until     DATETIME2(3) NULL,
    row_version      ROWVERSION
);

-- D-USER-ACCT: which accounts a regular user may see.
CREATE TABLE user_account (
    user_id     VARCHAR(8) COLLATE Latin1_General_BIN2 NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    account_id  BIGINT     NOT NULL,
    CONSTRAINT pk_user_account PRIMARY KEY (user_id, account_id)
);
CREATE INDEX ix_user_account_account ON user_account (account_id);

-- C-AUDIT
CREATE TABLE audit_log (
    audit_id     BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    recorded_at  DATETIME2(3) NOT NULL,
    actor        VARCHAR(8)   NULL,
    action       VARCHAR(40)  NOT NULL,
    target       VARCHAR(40)  NULL,
    fields       VARCHAR(400) NULL,
    client_addr  VARCHAR(64)  NULL
);

-- BC-2 Account and Customer -------------------------------------------------------------------
CREATE TABLE customer (
    customer_id        INT          NOT NULL PRIMARY KEY,
    first_name         VARCHAR(25)  NOT NULL DEFAULT '',
    middle_name        VARCHAR(25)  NOT NULL DEFAULT '',
    last_name          VARCHAR(25)  NOT NULL DEFAULT '',
    addr_line1         VARCHAR(50)  NOT NULL DEFAULT '',
    addr_line2         VARCHAR(50)  NOT NULL DEFAULT '',
    city               VARCHAR(50)  NOT NULL DEFAULT '',   -- CUST-ADDR-LINE-3 (Q-ENT-04)
    state_cd           VARCHAR(2)   NOT NULL DEFAULT '',
    country_cd         VARCHAR(3)   NOT NULL DEFAULT '',
    zip                VARCHAR(10)  NOT NULL DEFAULT '',
    phone1             VARCHAR(15)  NOT NULL DEFAULT '',
    phone2             VARCHAR(15)  NOT NULL DEFAULT '',
    ssn                CHAR(9)      NOT NULL,
    govt_issued_id     VARCHAR(20)  NOT NULL DEFAULT '',
    dob                DATE         NULL,
    eft_account_id     VARCHAR(10)  NOT NULL DEFAULT '',
    pri_card_holder    VARCHAR(1)   NOT NULL DEFAULT '',
    fico_score         SMALLINT     NOT NULL DEFAULT 0,
    row_version        ROWVERSION
);

CREATE TABLE account (
    account_id          BIGINT        NOT NULL PRIMARY KEY,
    active_status       VARCHAR(1)    NOT NULL DEFAULT '',
    current_balance     DECIMAL(12,2) NOT NULL DEFAULT 0,
    credit_limit        DECIMAL(12,2) NOT NULL DEFAULT 0,
    cash_credit_limit   DECIMAL(12,2) NOT NULL DEFAULT 0,
    open_date           DATE          NULL,
    expiration_date     DATE          NULL,
    reissue_date        DATE          NULL,
    curr_cycle_credit   DECIMAL(12,2) NOT NULL DEFAULT 0,
    curr_cycle_debit    DECIMAL(12,2) NOT NULL DEFAULT 0,
    addr_zip            VARCHAR(10)   NOT NULL DEFAULT '',
    group_id            VARCHAR(10)   COLLATE Latin1_General_BIN2 NOT NULL DEFAULT '',
    row_version         ROWVERSION
);

-- BC-3 Card ------------------------------------------------------------------------------------
CREATE TABLE card (
    card_num         CHAR(16)     COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    account_id       BIGINT       NOT NULL,
    cvv_cd           CHAR(3)      NOT NULL,
    embossed_name    VARCHAR(50)  NOT NULL DEFAULT '',
    expiration_date  DATE         NULL,
    active_status    VARCHAR(1)   NOT NULL DEFAULT '',
    row_version      ROWVERSION
);
CREATE INDEX ix_card_account ON card (account_id, card_num);

CREATE TABLE card_xref (
    card_num     CHAR(16) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    customer_id  INT      NOT NULL,
    account_id   BIGINT   NOT NULL
);
CREATE INDEX ix_card_xref_account ON card_xref (account_id, card_num);

-- BC-4 / BC-5 / BC-7 Transactions ---------------------------------------------------------------
-- *_ts_text keeps the timestamp text each COBOL writer produced (rendered on screens and reports);
-- *_ts is the parsed value used for ordering and date filters (Q-ENT-08).
CREATE TABLE card_transaction (
    tran_id            CHAR(16)      COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    type_cd            VARCHAR(2)    COLLATE Latin1_General_BIN2 NOT NULL,
    cat_cd             SMALLINT      NOT NULL,
    source             VARCHAR(10)   NOT NULL DEFAULT '',
    description        VARCHAR(100)  NOT NULL DEFAULT '',
    amount             DECIMAL(11,2) NOT NULL,
    merchant_id        INT           NOT NULL DEFAULT 0,
    merchant_name      VARCHAR(50)   NOT NULL DEFAULT '',
    merchant_city      VARCHAR(50)   NOT NULL DEFAULT '',
    merchant_zip       VARCHAR(10)   NOT NULL DEFAULT '',
    card_num           CHAR(16)      COLLATE Latin1_General_BIN2 NOT NULL,
    orig_ts_text       VARCHAR(26)   NOT NULL DEFAULT '',
    orig_ts            DATETIME2(6)  NULL,
    proc_ts_text       VARCHAR(26)   NOT NULL DEFAULT '',
    proc_ts            DATETIME2(6)  NULL,
    statement_run_id   BIGINT        NULL,   -- D-HISTORY: set when a statement run covers the row
    inserted_seq       BIGINT IDENTITY(1, 1) NOT NULL
);
CREATE INDEX ix_tran_card ON card_transaction (card_num, tran_id);
CREATE INDEX ix_tran_proc_ts ON card_transaction (proc_ts);
CREATE INDEX ix_tran_statement ON card_transaction (statement_run_id);

-- Online TRAN-ID generator (Q-COTRN02C-01, Q-COBIL00C-01): next = max(counter, highest numeric id) + 1.
CREATE TABLE id_counter (
    name   VARCHAR(40) NOT NULL PRIMARY KEY,
    value  BIGINT      NOT NULL
);
INSERT INTO id_counter (name, value) VALUES ('online_tran_id', 0);

CREATE TABLE tran_cat_balance (
    account_id  BIGINT        NOT NULL,
    type_cd     VARCHAR(2)    COLLATE Latin1_General_BIN2 NOT NULL,
    cat_cd      SMALLINT      NOT NULL,
    balance     DECIMAL(11,2) NOT NULL DEFAULT 0,
    legacy_filler VARCHAR(22) NOT NULL DEFAULT '',
    CONSTRAINT pk_tran_cat_balance PRIMARY KEY (account_id, type_cd, cat_cd)
);

CREATE TABLE posting_reject (
    reject_id       BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    run_id          BIGINT       NULL,
    business_date   DATE         NULL,
    record_text     CHAR(350)    NOT NULL,
    reason_code     SMALLINT     NOT NULL,
    reason_desc     VARCHAR(76)  NOT NULL,
    created_at      DATETIME2(3) NOT NULL
);

-- BC-8 Reference data ----------------------------------------------------------------------------
CREATE TABLE transaction_type (
    type_cd      VARCHAR(2)  COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    description  VARCHAR(50) NOT NULL DEFAULT '',
    legacy_filler VARCHAR(8) NOT NULL DEFAULT '',
    row_version  ROWVERSION
);

CREATE TABLE transaction_category (
    type_cd      VARCHAR(2)  COLLATE Latin1_General_BIN2 NOT NULL,
    cat_cd       SMALLINT    NOT NULL,
    description  VARCHAR(50) NOT NULL DEFAULT '',
    legacy_filler VARCHAR(4) NOT NULL DEFAULT '',
    row_version  ROWVERSION,
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_cd, cat_cd)
);

CREATE TABLE disclosure_group (
    group_id   VARCHAR(10)  COLLATE Latin1_General_BIN2 NOT NULL,
    type_cd    VARCHAR(2)   COLLATE Latin1_General_BIN2 NOT NULL,
    cat_cd     SMALLINT     NOT NULL,
    int_rate   DECIMAL(6,2) NOT NULL,
    legacy_filler VARCHAR(28) NOT NULL DEFAULT '',
    CONSTRAINT pk_disclosure_group PRIMARY KEY (group_id, type_cd, cat_cd)
);
