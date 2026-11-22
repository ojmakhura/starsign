package bw.co.starsign.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DisplayDateTest
{
    @Test
    void showsTimestampsAsDayMonthYearWithTime()
    {
        assertEquals("05-12-2026 21:59", NotificationServiceImpl.displayDate(LocalDateTime.of(2026, 12, 5, 21, 59, 59)));
        assertEquals("05-12-2026 21:59", NotificationServiceImpl.displayDate("2026-12-05T21:59:59"));
        assertEquals("26-11-2026 22:09", NotificationServiceImpl.displayDate("2026-11-26T22:09:37.883421701"));
    }

    @Test
    void showsDatesAsDayMonthYear()
    {
        assertEquals("05-12-2026", NotificationServiceImpl.displayDate(LocalDate.of(2026, 12, 5)));
        assertEquals("05-12-2026", NotificationServiceImpl.displayDate("2026-12-05"));
    }

    @Test
    void leavesEverythingElseAlone()
    {
        assertEquals("Contract signed 2026-12-05", NotificationServiceImpl.displayDate("Contract signed 2026-12-05"));
        assertEquals("WF-20261121-DR5FXN", NotificationServiceImpl.displayDate("WF-20261121-DR5FXN"));
        assertEquals(42, NotificationServiceImpl.displayDate(42));
        assertNull(NotificationServiceImpl.displayDate(null));
    }
}
