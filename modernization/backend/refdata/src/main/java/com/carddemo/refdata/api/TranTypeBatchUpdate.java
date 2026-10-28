package com.carddemo.refdata.api;

import java.nio.file.Path;

/**
 * JOB-MNTTRDB2 (COBTUPDT): applies a file of add, update, and delete records to the transaction types in one
 * transaction (B-UOW, Q-COBTUPDT-03). The program's DISPLAY lines go to the job log.
 */
public interface TranTypeBatchUpdate {

    BatchUpdateResult apply(Path inputFile);
}
