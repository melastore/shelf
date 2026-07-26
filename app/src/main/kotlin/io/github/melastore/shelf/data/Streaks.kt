package io.github.melastore.shelf.data

import java.time.LocalDate

/**
 * Current run of consecutive days a habit was kept, counting back from today. A day missed today
 * doesn't break a streak that is still alive from yesterday; a gap before that ends it.
 */
fun currentStreak(checkedDates: Collection<String>, today: LocalDate): Int {
	val done = checkedDates.toHashSet()
	var day = if (today.toString() in done) today else today.minusDays(1)
	var streak = 0
	while (day.toString() in done) {
		streak++
		day = day.minusDays(1)
	}
	return streak
}
