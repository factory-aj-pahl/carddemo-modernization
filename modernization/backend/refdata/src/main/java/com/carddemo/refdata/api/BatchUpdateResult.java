package com.carddemo.refdata.api;

/**
 * Outcome of a COBTUPDT run. Return code 0 when nothing was rejected, 4 when a record was rejected
 * (BR-COBTUPDT-07), 12 when the run stopped and was rolled back (Q-COBTUPDT-01, -04).
 *
 * @param read     records read (comments included)
 * @param added    rows inserted (0 when stopped)
 * @param updated  rows updated (0 when stopped)
 * @param deleted  rows deleted (0 when stopped)
 * @param rejected records rejected: invalid action or field, or no row for an update or delete
 * @param stopped  the input could not be read or a SQL error stopped the run; nothing was applied
 */
public record BatchUpdateResult(int read, int added, int updated, int deleted, int rejected, boolean stopped) {

    public int returnCode() {
        return stopped ? 12 : rejected > 0 ? 4 : 0;
    }
}
