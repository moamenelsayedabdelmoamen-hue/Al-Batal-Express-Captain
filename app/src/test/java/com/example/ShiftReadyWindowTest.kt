package com.example

import com.example.data.ShiftEntity
import com.example.data.ShiftManager
import com.example.data.ShiftStatus
import java.util.Calendar
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftReadyWindowTest {
    private fun mondayAt(hour: Int, minute: Int): Calendar =
        Calendar.getInstance(ShiftManager.CAIRO_TIME_ZONE).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.NOVEMBER)
            set(Calendar.DAY_OF_MONTH, 23) // Monday
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private val mondayShift = ShiftEntity(
        id = "captain_monday",
        captainUid = "captain_test",
        dayOfWeek = Calendar.MONDAY,
        dayNameArabic = "الإثنين",
        startTime = "10:00",
        endTime = "18:00",
        isDayOff = false
    )

    @Test
    fun readyWindow_isClosedMoreThanThirtyMinutesBeforeShift() {
        val status = ShiftManager.calculateShiftStatus(listOf(mondayShift), mondayAt(9, 29))
        assertTrue("At 31 minutes before start, ready must remain unavailable: $status", status is ShiftStatus.OutsideShift)
    }

    @Test
    fun readyWindow_opensAtExactlyThirtyMinutesBeforeShift() {
        val status = ShiftManager.calculateShiftStatus(listOf(mondayShift), mondayAt(9, 30))
        assertTrue("At 30 minutes before start, ready must be available: $status", status is ShiftStatus.UpcomingSoon)
    }

    @Test
    fun readyWindow_remainsAvailableDuringFinalMinuteBeforeShift() {
        val status = ShiftManager.calculateShiftStatus(listOf(mondayShift), mondayAt(9, 59))
        assertTrue("At one minute before start, ready must remain available: $status", status is ShiftStatus.UpcomingSoon)
    }
}
