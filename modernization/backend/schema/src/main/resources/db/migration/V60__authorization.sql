-- BC-10 Authorizations (spec/entities.md ENT-AUTH-SUMMARY, ENT-AUTH-DETAIL, ENT-AUTH-FRAUD, ENT-AUTH-ERROR-LOG).
-- Replaces the IMS database DBPAUTP0 (root PAUTSUM0, child PAUTDTL1), the Db2 table CARDDEMO.AUTHFRDS
-- and the COPAUA0C error log. All money columns are DECIMAL(12,2) (Q-ENT-26).
-- account_id is a logical reference to account (no foreign key: the sample loader replaces accounts).
-- Text columns hold the COBOL field content with trailing spaces removed; the unload writers pad them back.
-- legacy_* columns keep bytes of migrated segments that have no column (FILLER, odd status bytes, the
-- 2-byte value the IMS unload writes before each segment) so a migrated database unloads byte for byte;
-- rows created by the target leave them NULL.

CREATE TABLE pending_auth_summary (
    account_id        BIGINT        NOT NULL PRIMARY KEY,
    customer_id       INT           NOT NULL DEFAULT 0,
    auth_status       VARCHAR(1)    NOT NULL DEFAULT '',
    account_status    VARCHAR(10)   NOT NULL DEFAULT '',   -- PA-ACCOUNT-STATUS X(02) OCCURS 5
    credit_limit      DECIMAL(12,2) NOT NULL DEFAULT 0,
    cash_limit        DECIMAL(12,2) NOT NULL DEFAULT 0,
    credit_balance    DECIMAL(12,2) NOT NULL DEFAULT 0,
    cash_balance      DECIMAL(12,2) NOT NULL DEFAULT 0,
    approved_count    INT           NOT NULL DEFAULT 0,
    declined_count    INT           NOT NULL DEFAULT 0,
    approved_amt      DECIMAL(12,2) NOT NULL DEFAULT 0,
    declined_amt      DECIMAL(12,2) NOT NULL DEFAULT 0,
    legacy_status     VARBINARY(11) NULL,   -- raw status bytes when they are not text (sample: X'00')
    legacy_filler     VARBINARY(34) NULL,
    legacy_rba        INT           NULL,
    row_version       ROWVERSION
);

-- Q-ENT-27 / Q-COPAUA0C-11: auth_ts is the full insert timestamp; (auth_ts, auth_id) is the unique key,
-- newest first. The IMS key (99999 - YYDDD, 999999999 - HHMMSSmmm) is derived from auth_ts on unload.
CREATE TABLE pending_auth_detail (
    auth_id                 BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    account_id              BIGINT        NOT NULL,
    auth_ts                 DATETIME2(3)  NOT NULL,
    auth_orig_date          VARCHAR(6)    NOT NULL DEFAULT '',
    auth_orig_time          VARCHAR(6)    NOT NULL DEFAULT '',
    card_num                CHAR(16)      COLLATE Latin1_General_BIN2 NOT NULL,
    auth_type               VARCHAR(4)    NOT NULL DEFAULT '',
    card_expiry_date        VARCHAR(4)    NOT NULL DEFAULT '',
    message_type            VARCHAR(6)    NOT NULL DEFAULT '',
    message_source          VARCHAR(6)    NOT NULL DEFAULT '',
    auth_id_code            VARCHAR(6)    NOT NULL DEFAULT '',
    auth_resp_code          VARCHAR(2)    NOT NULL DEFAULT '',
    auth_resp_reason        VARCHAR(4)    NOT NULL DEFAULT '',
    processing_code         VARCHAR(6)    NOT NULL DEFAULT '',
    transaction_amt         DECIMAL(12,2) NOT NULL DEFAULT 0,
    approved_amt            DECIMAL(12,2) NOT NULL DEFAULT 0,
    merchant_category_code  VARCHAR(4)    NOT NULL DEFAULT '',
    acqr_country_code       VARCHAR(3)    NOT NULL DEFAULT '',
    pos_entry_mode          VARCHAR(2)    NOT NULL DEFAULT '',
    merchant_id             VARCHAR(15)   NOT NULL DEFAULT '',
    merchant_name           VARCHAR(22)   NOT NULL DEFAULT '',
    merchant_city           VARCHAR(13)   NOT NULL DEFAULT '',
    merchant_state          VARCHAR(2)    NOT NULL DEFAULT '',
    merchant_zip            VARCHAR(9)    NOT NULL DEFAULT '',
    transaction_id          VARCHAR(15)   NOT NULL DEFAULT '',
    match_status            VARCHAR(1)    NOT NULL DEFAULT '',
    auth_fraud              VARCHAR(1)    NOT NULL DEFAULT '',
    fraud_rpt_date          DATE          NULL,   -- Q-ENT-28: one DATE, shown MM/DD/YY
    legacy_filler           VARBINARY(17) NULL,
    legacy_rba              INT           NULL,
    row_version             ROWVERSION
);
CREATE UNIQUE INDEX ux_pending_auth_detail_account_ts ON pending_auth_detail (account_id, auth_ts DESC, auth_id DESC);
CREATE INDEX ix_pending_auth_detail_ts ON pending_auth_detail (auth_ts);

-- CARDDEMO.AUTHFRDS (COPAUS2C). Q-COPAUS2C-03: the key includes the unique authorization key.
CREATE TABLE auth_fraud_report (
    card_num                CHAR(16)      COLLATE Latin1_General_BIN2 NOT NULL,
    auth_ts                 DATETIME2(3)  NOT NULL,
    auth_id                 BIGINT        NOT NULL,
    auth_type               VARCHAR(4)    NULL,
    card_expiry_date        VARCHAR(4)    NULL,
    message_type            VARCHAR(6)    NULL,
    message_source          VARCHAR(6)    NULL,
    auth_id_code            VARCHAR(6)    NULL,
    auth_resp_code          VARCHAR(2)    NULL,
    auth_resp_reason        VARCHAR(4)    NULL,
    processing_code         VARCHAR(6)    NULL,
    transaction_amt         DECIMAL(12,2) NULL,
    approved_amt            DECIMAL(12,2) NULL,
    merchant_category_code  VARCHAR(4)    NULL,
    acqr_country_code       VARCHAR(3)    NULL,
    pos_entry_mode          SMALLINT      NULL,
    merchant_id             VARCHAR(15)   NULL,
    merchant_name           VARCHAR(22)   NULL,
    merchant_city           VARCHAR(13)   NULL,
    merchant_state          VARCHAR(2)    NULL,
    merchant_zip            VARCHAR(9)    NULL,
    transaction_id          VARCHAR(15)   NULL,
    match_status            VARCHAR(1)    NULL,
    auth_fraud              VARCHAR(1)    NULL,
    fraud_rpt_date          DATE          NULL,
    acct_id                 BIGINT        NULL,
    cust_id                 INT           NULL,
    CONSTRAINT pk_auth_fraud_report PRIMARY KEY (card_num, auth_ts, auth_id)
);

-- CCPAUERY error records of COPAUA0C (also written to the application log). event_key holds the masked
-- card number (Q-COPAUA0C-13).
CREATE TABLE auth_error_log (
    error_id     BIGINT IDENTITY(1, 1) NOT NULL PRIMARY KEY,
    logged_at    DATETIME2(3) NOT NULL,
    err_date     CHAR(6)      NOT NULL,
    err_time     CHAR(6)      NOT NULL,
    application  VARCHAR(8)   NOT NULL,
    program      VARCHAR(8)   NOT NULL,
    location     VARCHAR(4)   NOT NULL,
    err_level    CHAR(1)      NOT NULL,
    subsystem    CHAR(1)      NOT NULL,
    code1        VARCHAR(9)   NOT NULL DEFAULT '',
    code2        VARCHAR(9)   NOT NULL DEFAULT '',
    message      VARCHAR(50)  NOT NULL,
    event_key    VARCHAR(20)  NOT NULL DEFAULT ''
);
