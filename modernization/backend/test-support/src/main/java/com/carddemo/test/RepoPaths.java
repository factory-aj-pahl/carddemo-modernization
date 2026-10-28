package com.carddemo.test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the repository root (the directory holding {@code app/}) from the test working directory. */
public final class RepoPaths {

    private RepoPaths() {
    }

    public static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("app/cbl")) && Files.isDirectory(p.resolve("modernization"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    public static Path asciiData() {
        return root().resolve("app/data/ASCII");
    }

    public static Path usrsec() {
        return root().resolve("app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS");
    }
}
