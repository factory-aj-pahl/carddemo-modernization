package com.carddemo.dataexchange.internal;

/**
 * A legacy input file read sequentially: {@link #read()} returns the next fixed-length record, or null at
 * end of file (status {@code 10}). Failures throw {@link FileStatusException}.
 */
interface RecordSource {

    void open();

    String read();

    void close();
}
