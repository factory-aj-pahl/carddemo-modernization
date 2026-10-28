-- Db2 module (app-transaction-type-db2, TRNTYCAT.ddl): a category belongs to an existing type, and a type
-- with categories cannot be deleted (FOREIGN KEY ... ON DELETE RESTRICT; SQL Server's default NO ACTION
-- rejects the delete the same way). COTRTLIC and COBTUPDT report the violation (ERR-COTRTLIC-13).
ALTER TABLE transaction_category ADD CONSTRAINT fk_transaction_category_type
    FOREIGN KEY (type_cd) REFERENCES transaction_type (type_cd);
