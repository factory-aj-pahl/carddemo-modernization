package com.carddemo.dataexchange.internal;

import java.io.IOException;
import java.nio.file.Path;

/** Creates output files (replacing an existing file, as the JCL delete-and-allocate steps do). */
interface Sinks {

    RecordSink create(Path path) throws IOException;
}
