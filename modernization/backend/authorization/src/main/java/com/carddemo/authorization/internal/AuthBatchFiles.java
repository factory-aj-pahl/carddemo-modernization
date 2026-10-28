package com.carddemo.authorization.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.springframework.stereotype.Component;

/**
 * Sequential files of the unload and load jobs. Unload files hold full card numbers, so they are created mode 600
 * (D-PII-FILES, Q-PAUDBUNL-10, Q-DBUNLDGS-09); an existing file is replaced (Q-UNLDPADB-01).
 */
@Component
public class AuthBatchFiles {

    public FileChannel create(Path path) throws IOException {
        Files.deleteIfExists(path);
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return FileChannel.open(path, StandardOpenOption.WRITE);
    }

    public InputStream open(Path path) throws IOException {
        return Channels.newInputStream(FileChannel.open(path, StandardOpenOption.READ));
    }

    /** The COBOL file status an OPEN failure would give: 35 file not found, 37 no permission, 30 other. */
    static String openStatus(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "35";
        }
        return e instanceof AccessDeniedException ? "37" : "30";
    }
}
