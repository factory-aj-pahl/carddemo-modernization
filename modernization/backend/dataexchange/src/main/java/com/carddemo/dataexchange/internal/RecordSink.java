package com.carddemo.dataexchange.internal;

import java.io.IOException;

/** An output file written record by record; each write reaches the file before it returns. */
interface RecordSink {

    void write(byte[] data) throws IOException;

    void close() throws IOException;
}
