package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.test.Covers;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestDatabaseConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** The report job CORPT00C requests (JOB-TRANREPT), selected by processing date. */
@SpringBootTest(classes = {BatchApplication.class, TestDatabaseConfig.class, SampleData.class})
class ReportRequestJobTest extends CycleJobTestSupport {

    static final Pattern DETAIL = Pattern.compile("^(\\d{16}) \\d{11} .*");

    private List<String> yearly(int year) throws IOException {
        Path report = dir.resolve("TRANREPT.txt");
        assertThat(cli.execute("TRANREPT", "reportFile=" + report, "startDate=" + year + "-01-01", "endDate=" + year + "-12-31")).isZero();
        return lines(report).stream().map(DETAIL::matcher).filter(m -> m.matches()).map(m -> m.group(1)).toList();
    }

    @Test
    @Covers({"TS-CORPT00C-15", "BR-CORPT00C-10", "Q-CORPT00C-06"})
    void unposted_transactions_with_a_blank_processing_date_never_appear() throws IOException {
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        assertThat(count("SELECT COUNT(*) FROM card_transaction WHERE proc_ts IS NULL")).isGreaterThanOrEqualTo(300);
        assertThat(yearly(2022)).isEmpty();
    }

    @Test
    @Covers({"TS-CORPT00C-15", "BR-CORPT00C-10"})
    void posted_transactions_are_listed_grouped_by_card() throws IOException {
        postSample("2022-06-10");
        // POSTTRAN stamps TRAN-PROC-TS with the time of the run, so the posted rows fall in the current year.
        int year = LocalDate.now().getYear();
        List<String> ids = yearly(year);
        assertThat(ids).hasSize(count("SELECT COUNT(*) FROM card_transaction WHERE YEAR(proc_ts) = ?", year))
            .hasSizeGreaterThanOrEqualTo(262);
        List<String> cards = ids.stream()
            .map(id -> jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = ?", String.class, id)).toList();
        assertThat(cards).isSorted();
    }
}
