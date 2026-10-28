package com.carddemo.dataexchange.internal;

import com.carddemo.common.codec.FixedWidth;
import com.carddemo.test.RepoPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Sample records as the tables return them (key order, padded), plus stub files with injected failures. */
final class Fixtures {

    private Fixtures() {
    }

    static List<String> sample(String file, int length) {
        try {
            return Files.readAllLines(RepoPaths.asciiData().resolve(file), StandardCharsets.ISO_8859_1).stream()
                .filter(l -> !l.isBlank() && !l.equals("\r")).map(l -> FixedWidth.normalize(l, length)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> cards() {
        return sample("carddata.txt", 150);
    }

    static List<String> xrefs() {
        return sample("cardxref.txt", 50);
    }

    static List<String> customers() {
        return sample("custdata.txt", 500);
    }

    /** Accounts after the Q-ENT-02 migration rule (group ID moved out of the ZIP slot), as loaded. */
    static List<String> accounts() {
        return sample("acctdata.txt", 300).stream().map(r -> r.substring(0, 102) + " ".repeat(10) + r.substring(102, 112) + r.substring(122))
            .toList();
    }

    /** The daily transactions as TRANSACT records. */
    static List<String> transactions() {
        return sample("dailytran.txt", 350);
    }

    /** An in-memory file; a status set for a step makes that step fail. */
    static final class StubFile implements RecordSource {
        final List<String> records;
        String openStatus;
        String closeStatus;
        String readStatus;
        int failAtRead = -1;
        int reads;
        boolean closed;

        StubFile(List<String> records) {
            this.records = new ArrayList<>(records);
        }

        StubFile failOpen(String status) {
            openStatus = status;
            return this;
        }

        StubFile failRead(int index, String status) {
            failAtRead = index;
            readStatus = status;
            return this;
        }

        StubFile failClose(String status) {
            closeStatus = status;
            return this;
        }

        @Override
        public void open() {
            if (openStatus != null) {
                throw new FileStatusException(openStatus);
            }
        }

        @Override
        public String read() {
            if (reads == failAtRead) {
                throw new FileStatusException(readStatus);
            }
            return reads < records.size() ? records.get(reads++) : null;
        }

        @Override
        public void close() {
            closed = true;
            if (closeStatus != null) {
                throw new FileStatusException(closeStatus);
            }
        }
    }

    /** Real files; writing to {@code failing} throws {@code error} on write number {@code failAt} (0-based). */
    static final class FailingSinks implements Sinks {
        final Path failing;
        final int failAt;
        final String error;
        final List<Path> closed = new ArrayList<>();

        FailingSinks(Path failing, int failAt, String error) {
            this.failing = failing;
            this.failAt = failAt;
            this.error = error;
        }

        @Override
        public RecordSink create(Path path) throws IOException {
            RecordSink real = new FileSinks().create(path);
            return new RecordSink() {
                int writes;

                @Override
                public void write(byte[] data) throws IOException {
                    if (path.equals(failing) && writes++ == failAt) {
                        throw new IOException(error);
                    }
                    real.write(data);
                }

                @Override
                public void close() throws IOException {
                    closed.add(path);
                    real.close();
                }
            };
        }
    }
}
