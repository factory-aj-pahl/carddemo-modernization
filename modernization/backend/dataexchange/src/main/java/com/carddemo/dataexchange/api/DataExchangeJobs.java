package com.carddemo.dataexchange.api;

import java.nio.file.Path;

/**
 * BC-9 batch jobs (spec/programs CBACT01C, CBACT02C, CBACT03C, CBCUS01C, CBEXPORT, CBIMPORT). Each job
 * writes its program's SYSOUT lines to the job log. An abend path displays the program's messages and
 * returns {@link JobResult#abended()} = true (B-ABEND); the caller maps it to exit code 99.
 */
public interface DataExchangeJobs {

    /** JOB-READCARD (CBACT02C): the card table in card-number order, masked (Q-CBACT02C-01). */
    JobResult listCards();

    /** JOB-READXREF (CBACT03C): the card cross-reference in card-number order, once per record, masked. */
    JobResult listXrefs();

    /** JOB-READCUST (CBCUS01C): the customer table in ID order, once per record, PII masked. */
    JobResult listCustomers();

    /** JOB-READACCT (CBACT01C): account listing and the three demonstration extract files. */
    JobResult extractAccounts(AccountExtractFiles files);

    /** CBEXPORT: customers, accounts, xrefs, transactions, and cards into one 500-byte multi-record file. */
    JobResult exportAll(Path exportFile);

    /** CBIMPORT: split an export file back into the five legacy record files plus the error file. */
    JobResult importAll(Path exportFile, ImportFiles files);
}
