package com.alarmhub.app.domain

import com.alarmhub.app.domain.Fixtures.ZONE
import com.alarmhub.app.domain.Fixtures.at
import com.alarmhub.app.domain.Fixtures.dailyAlarm
import com.alarmhub.app.domain.Fixtures.group
import com.alarmhub.app.domain.Fixtures.workdayAlarm
import com.alarmhub.app.domain.schedule.AlarmStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock seam. These tests exist to prove the domain can be driven entirely by an injected
 * instant — the same call, two different clocks, two different answers, nothing else changed.
 */
class DomainServicesTest {

    private val calculator = DomainServices.calculator(Fixtures.bundledCalendar)
    private val resolver = DomainServices.resolver(Fixtures.bundledCalendar)

    @Test
    fun `a fixed time source replaces the system clock`() {
        val alarm = dailyAlarm(hour = 7)
        val beforeRing = at("2025-10-20 06:00:00")
        val afterRing = at("2025-10-20 08:00:00")

        assertEquals(
            at("2025-10-20 07:00:00"),
            DomainServices.nextRing(calculator, alarm, group(), ZONE, TimeSource.fixed(beforeRing)),
        )
        assertEquals(
            at("2025-10-21 07:00:00"),
            DomainServices.nextRing(calculator, alarm, group(), ZONE, TimeSource.fixed(afterRing)),
        )
    }

    @Test
    fun `status is taken as of the injected instant`() {
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = at("2025-10-22 00:00:00"))

        assertEquals(
            AlarmStatus.PAUSED,
            DomainServices.statusOf(calculator, alarm, group(), ZONE, TimeSource.fixed(at("2025-10-20 06:00:00"))).status,
        )
        assertEquals(
            AlarmStatus.SCHEDULED,
            DomainServices.statusOf(calculator, alarm, group(), ZONE, TimeSource.fixed(at("2025-10-22 06:00:00"))).status,
        )
    }

    /** PRD §5.3's row 4 through the production-shaped entry point. */
    @Test
    fun `preview pause uses the injected instant and matches the PRD table`() {
        val alarms = listOf(workdayAlarm(hour = 7, minute = 0))

        val result = DomainServices.previewPause(
            resolver,
            alarms,
            group(),
            days = 1,
            zone = ZONE,
            timeSource = TimeSource.fixed(at("2025-10-24 20:00:00")),
        )

        assertEquals(Fixtures.day("2025-10-28"), result.resumeAt)
        assertEquals(listOf(Fixtures.day("2025-10-27")), result.skippedRingDays)
    }

    @Test
    fun `the system time source reads a plausible wall clock`() {
        val now = TimeSource.system.nowMillis()

        // Not a real assertion about the date — just that the seam is wired to something live and
        // that it is in milliseconds since the epoch rather than, say, seconds.
        assertTrue("expected an epoch-millis value, got $now", now > 1_600_000_000_000L)
    }
}
