package com.carddemo.dataexchange.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Output files created with mode 600 (owner read and write only): the export and import files carry
 * PAN, CVV, and SSN (D-PII-FILES, Q-CBEXPORT-01, Q-CBIMPORT-10).
 */
final class FileSinks implements Sinks {

    @Override
    public RecordSink create(Path path) throws IOException {
        Files.deleteIfExists(path);
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE);
        return new RecordSink() {
            @Override
            public void write(byte[] data) throws IOException {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) {
                    ch.write(b);
                }
            }

            @Override
            public void close() throws IOException {
                ch.close();
            }
        };
    }
}
