package com.carddemo.batch;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.batch.JobLog;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The BC-9 data exchange jobs through the batch command line, reading the database tables. */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class})
class DataExchangeCommandLineTest {

    @Autowired
    BatchCommandLine cli;
    @Autowired
    JobLog log;
    @TempDir
    Path dir;

    @BeforeEach
    void load() throws IOException {
        cli.execute("reset");
        Path in = Files.createDirectories(dir.resolve("in"));
        for (String name : List.of("custdata.txt", "acctdata.txt", "cardxref.txt", "carddata.txt", "discgrp.txt")) {
            Files.copy(RepoPaths.asciiData().resolve(name), in.resolve(name));
        }
        Files.copy(RepoPaths.asciiData().resolve("dailytran.txt"), in.resolve("transact.txt"));
        assertThat(cli.execute("load", in.toString())).isZero();
        log.startCapture();
    }

    @Test
    @Covers({"TS-CBACT01C-01", "TS-CBACT01C-02", "Q-CBACT01C-07"})
    void readacct_lists_the_account_table_and_writes_the_extracts_with_the_migrated_group_id() throws IOException {
        int rc = cli.execute("READACCT", "outFile=" + dir.resolve("pscomp"), "arryFile=" + dir.resolve("arryps"),
            "vbrcFile=" + dir.resolve("vbps"));
        assertThat(rc).isEqualTo(ExitCodes.OK);
        List<String> out = log.captured();
        assertThat(out).hasSize(2 + 50 * 15);
        assertThat(out).filteredOn(l -> l.startsWith("ACCT-GROUP-ID")).hasSize(50)
            .allMatch(l -> l.equals("ACCT-GROUP-ID           :A000000000"));
        byte[] pscomp = Files.readAllBytes(dir.resolve("pscomp"));
        assertThat(pscomp).hasSize(50 * 107);
        assertThat(new String(pscomp, 97, 10, ISO_8859_1)).isEqualTo("A000000000");
        assertThat(Files.size(dir.resolve("arryps"))).isEqualTo(50L * 110);
        assertThat(Files.size(dir.resolve("vbps"))).isEqualTo(50L * (16 + 43));
    }

    @Test
    @Covers({"TS-CBEXPORT-09", "TS-CBIMPORT-02", "Q-CBIMPORT-03"})
    void export_of_the_tables_imports_back_to_the_table_contents_byte_for_byte() throws IOException {
        Path exp = dir.resolve("expfile.dat");
        assertThat(cli.execute("CBEXPORT", "exportFile=" + exp)).isEqualTo(ExitCodes.OK);
        assertThat(Files.size(exp)).isEqualTo(500L * 500);
        assertThat(log.captured()).contains("CBEXPORT: Total Records Exported: 000000500");
        Path tables = Files.createDirectories(dir.resolve("tables"));
        assertThat(cli.execute("export", tables.toString(), "files=custdata.txt,acctdata.txt,cardxref.txt,transact.txt,carddata.txt"))
            .isEqualTo(ExitCodes.OK);
        int rc = cli.execute("CBIMPORT", "exportFile=" + exp, "custOut=" + dir.resolve("custout"), "acctOut=" + dir.resolve("acctout"),
            "xrefOut=" + dir.resolve("xrefout"), "trnxOut=" + dir.resolve("trnxout"), "cardOut=" + dir.resolve("cardout"),
            "errOut=" + dir.resolve("errout"));
        assertThat(rc).isEqualTo(ExitCodes.OK);
        assertThat(Files.readString(dir.resolve("custout"), ISO_8859_1)).isEqualTo(Files.readString(tables.resolve("custdata.txt"), ISO_8859_1));
        assertThat(Files.readString(dir.resolve("acctout"), ISO_8859_1)).isEqualTo(Files.readString(tables.resolve("acctdata.txt"), ISO_8859_1));
        assertThat(Files.readString(dir.resolve("xrefout"), ISO_8859_1)).isEqualTo(Files.readString(tables.resolve("cardxref.txt"), ISO_8859_1));
        assertThat(Files.readString(dir.resolve("trnxout"), ISO_8859_1)).isEqualTo(Files.readString(tables.resolve("transact.txt"), ISO_8859_1));
        assertThat(Files.readString(dir.resolve("cardout"), ISO_8859_1)).isEqualTo(Files.readString(tables.resolve("carddata.txt"), ISO_8859_1));
        assertThat(Files.size(dir.resolve("errout"))).isZero();
    }

    @Test
    @Covers({"TS-CBACT02C-01", "Q-CBACT02C-01", "B-MASK"})
    void readcard_lists_the_card_table_masked() {
        assertThat(cli.execute("READCARD")).isEqualTo(ExitCodes.OK);
        List<String> out = log.captured();
        assertThat(out).hasSize(52);
        assertThat(out.subList(1, 51)).allMatch(l -> l.startsWith("************") && l.substring(27, 30).equals("***"));
    }

    @Test
    @Covers({"TS-CBIMPORT-06", "B-ABEND"})
    void import_of_a_missing_file_exits_99_and_missing_parameters_are_a_usage_error() {
        String[] outs = {"custOut=" + dir.resolve("c"), "acctOut=" + dir.resolve("a"), "xrefOut=" + dir.resolve("x"),
            "trnxOut=" + dir.resolve("t"), "cardOut=" + dir.resolve("d"), "errOut=" + dir.resolve("e")};
        String[] args = new String[outs.length + 2];
        args[0] = "CBIMPORT";
        args[1] = "exportFile=" + dir.resolve("absent.dat");
        System.arraycopy(outs, 0, args, 2, outs.length);
        assertThat(cli.execute(args)).isEqualTo(ExitCodes.ABEND);
        assertThat(log.captured()).containsExactly("CBIMPORT: Starting Customer Data Import", "ERROR: Cannot open EXPORT-INPUT, Status: 35",
            "CBIMPORT: ABENDING PROGRAM");
        assertThat(cli.execute("CBIMPORT", "exportFile=x")).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.execute("READACCT")).isEqualTo(ExitCodes.USAGE);
        assertThat(cli.execute("CBEXPORT")).isEqualTo(ExitCodes.USAGE);
    }
}
