package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.date.CalendarDates;
import com.carddemo.common.date.CalendarDates.Result;
import com.carddemo.test.Covers;
import org.junit.jupiter.api.Test;

/** CSUTLDTC as the shared-kernel calendar check (pure function, no Spring context). */
class CalendarDatesTest {

    @Test
    @Covers({"TS-CSUTLDTC-01", "BR-CSUTLDTC-01", "BR-CSUTLDTC-02", "BR-CSUTLDTC-03", "BR-CSUTLDTC-04", "Q-CSUTLDTC-01",
        "Q-CSUTLDTC-02"})
    void a_real_date_is_valid() {
        assertThat(CalendarDates.check("2022-06-15", CalendarDates.ISO)).isEqualTo(Result.VALID);
        assertThat(CalendarDates.check("20220615", CalendarDates.COMPACT)).isEqualTo(Result.VALID);
        assertThat(CalendarDates.check("9999-12-31", CalendarDates.ISO)).isEqualTo(Result.VALID);
        assertThat(CalendarDates.check("1582-10-15", CalendarDates.ISO)).isEqualTo(Result.VALID);
    }

    @Test
    @Covers({"TS-CSUTLDTC-02", "TS-CSUTLDTC-03", "BR-CSUTLDTC-03"})
    void leap_years_follow_the_gregorian_calendar() {
        assertThat(CalendarDates.check("2023-02-29", CalendarDates.ISO)).isEqualTo(Result.INVALID);
        assertThat(CalendarDates.check("2024-02-29", CalendarDates.ISO)).isEqualTo(Result.VALID);
        assertThat(CalendarDates.check("1900-02-29", CalendarDates.ISO)).isEqualTo(Result.INVALID);
        assertThat(CalendarDates.check("2000-02-29", CalendarDates.ISO)).isEqualTo(Result.VALID);
    }

    @Test
    @Covers({"TS-CSUTLDTC-04", "BR-CSUTLDTC-05", "Q-CSUTLDTC-05", "DF-8"})
    void dates_before_the_gregorian_start_are_out_of_range_and_not_valid_for_any_caller() {
        assertThat(CalendarDates.check("1500-01-01", CalendarDates.ISO)).isEqualTo(Result.OUT_OF_RANGE);
        assertThat(CalendarDates.check("1582-10-14", CalendarDates.ISO)).isEqualTo(Result.OUT_OF_RANGE);
        assertThat(CalendarDates.check("0022-01-01", CalendarDates.ISO)).isEqualTo(Result.OUT_OF_RANGE);
        assertThat(CalendarDates.isValid("1500-01-01", CalendarDates.ISO)).isFalse();
    }

    @Test
    @Covers({"TS-CSUTLDTC-05", "TS-CSUTLDTC-06", "BR-CSUTLDTC-03", "ERR-CSUTLDTC-01", "Q-CSUTLDTC-03"})
    void impossible_or_malformed_dates_are_invalid_and_never_throw() {
        for (String bad : new String[] {"2022-13-01", "2022-06-31", "2022-00-10", "2022-06-00", "0000-01-01", "2022-6-15 ",
            "ABCD-EF-GH", "2022/06/15", "", "2022-06-1"}) {
            assertThat(CalendarDates.check(bad, CalendarDates.ISO)).as(bad).isEqualTo(Result.INVALID);
        }
        assertThat(CalendarDates.check(null, CalendarDates.ISO)).isEqualTo(Result.INVALID);
        assertThat(CalendarDates.check("2022061", CalendarDates.COMPACT)).isEqualTo(Result.INVALID);
        assertThatThrownBy(() -> CalendarDates.check("2022", "YYYY")).isInstanceOf(IllegalArgumentException.class);
    }
}
