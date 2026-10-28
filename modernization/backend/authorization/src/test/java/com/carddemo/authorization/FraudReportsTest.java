package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.internal.FraudReports;
import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.test.Covers;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** COPAUS2C error text for a Db2 failure that cannot be produced on the test database (SQLCODE -904). */
class FraudReportsTest {

    @Test
    @Covers({"TS-COPAUS2C-04", "ERR-COPAUS2C-01"})
    void an_unavailable_table_fails_with_the_sqlcode_and_state() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(
            new DataAccessResourceFailureException("unavailable", new SQLException("resource unavailable", "57011", 904)));
        Detail d = mock(Detail.class);
        when(d.authTs()).thenReturn(LocalDateTime.of(2023, 11, 16, 9, 17, 49, 523_000_000));
        when(d.cardNum()).thenReturn(AuthFixtures.CARD_7);
        FraudReports.Result r = new FraudReports(jdbc).record(7, 7, d, "F", LocalDate.of(2024, 3, 5));
        assertThat(r.success()).isFalse();
        assertThat(r.message()).isEqualTo(" SYSTEM ERROR DB2: CODE:-000904, STATE: 57011");
    }
}
