package com.carddemo.authorization.api;

import java.nio.file.Path;

/**
 * BC-10 batch jobs on the pending-authorization tables (spec/programs CBPAUP0C, PAUDBUNL, PAUDBLOD, DBUNLDGS). Each
 * job writes its program's SYSOUT lines to the job log and returns the step return code: 0, or 16 where the program
 * would end through 9999-ABEND.
 */
public interface AuthorizationJobs {

    /** CBPAUP0J / CBPAUP0C: purge expired details. {@code sysin} is the parameter card {@code EE,CCCCC,DDDDD,F}. */
    AuthorizationJobResult purgeExpired(String sysin);

    /** UNLDPADB / PAUDBUNL: summaries to a 100-byte file, details (parent key + segment) to a 206-byte file. */
    AuthorizationJobResult unload(Path rootFile, Path childFile);

    /** LOADPADB / PAUDBLOD: load the two PAUDBUNL files. */
    AuthorizationJobResult load(Path rootFile, Path childFile);

    /** UNLDGSAM / DBUNLDGS: the GSAM unload; detail records carry the parent key as in PAUDBUNL (Q-DBUNLDGS-01). */
    AuthorizationJobResult unloadGsam(Path summaryFile, Path detailFile);
}
