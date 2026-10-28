-- CORPT00C report requests (ledger Q-CORPT00C-07, -09, -10): one row per carddemo_transaction_report
-- DAG run started from the report screen. The row is the access check for the report download
-- (own runs; admins any), so it is written before the run is launched and removed if the launch fails.
CREATE TABLE report_run (
    dag_run_id        VARCHAR(250) COLLATE Latin1_General_BIN2 NOT NULL PRIMARY KEY,
    report_type       VARCHAR(7)   NOT NULL CONSTRAINT ck_report_run_type CHECK (report_type IN ('Monthly', 'Yearly', 'Custom')),
    start_date        DATE         NOT NULL,
    end_date          DATE         NOT NULL,
    user_id           VARCHAR(8)   COLLATE Latin1_General_BIN2 NOT NULL,
    requested_at      DATETIME2(3) NOT NULL,
    state             VARCHAR(20)  NOT NULL,
    state_checked_at  DATETIME2(3) NULL
);
CREATE INDEX ix_report_run_user ON report_run (user_id, requested_at DESC);
CREATE INDEX ix_report_run_requested ON report_run (requested_at DESC);
