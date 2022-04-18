package org.roboquant.common

import kotlin.test.*
class ZonedPeriodTest {

    @Test
    fun conversions() {
        assertEquals(1, 1.minutes.toMinutes())
        assertEquals(2, 2.hours.toHours())
        assertEquals(3, 3.days.toDays())
        assertEquals(4 * 7, 4.weeks.toDays())
    }

}