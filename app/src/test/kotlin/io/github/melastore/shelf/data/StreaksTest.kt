package io.github.melastore.shelf.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StreaksTest {

	private val today = LocalDate.of(2026, 7, 25)

	private fun back(vararg daysAgo: Long) = daysAgo.map { today.minusDays(it).toString() }

	@Test fun noCheckedDaysIsZero() {
		assertEquals(0, currentStreak(emptyList(), today))
	}

	@Test fun todayOnlyIsOne() {
		assertEquals(1, currentStreak(back(0), today))
	}

	@Test fun consecutiveDaysEndingTodayCount() {
		assertEquals(3, currentStreak(back(0, 1, 2), today))
	}

	@Test fun streakAliveFromYesterdaySurvivesAMissedToday() {
		assertEquals(2, currentStreak(back(1, 2), today))
	}

	@Test fun aGapBeforeYesterdayEndsTheStreak() {
		assertEquals(0, currentStreak(back(2, 3), today))
	}

	@Test fun onlyTheRunTouchingTodayCounts() {
		assertEquals(2, currentStreak(back(0, 1, 4, 5, 6), today))
	}
}
