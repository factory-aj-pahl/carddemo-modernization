package com.carddemo.dataexchange.api;

import java.nio.file.Path;

/**
 * CBACT01C outputs: OUTFILE (107-byte fixed records), ARRYFILE (110-byte fixed records), VBRCFILE
 * (variable records, each written with a 4-byte z/OS record descriptor word).
 */
public record AccountExtractFiles(Path outFile, Path arrayFile, Path vbrcFile) {
}
