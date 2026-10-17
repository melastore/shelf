package io.github.melastore.shelf.ui

import android.app.AlarmManager
import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.ShelfCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RehideTimerTest {

	// One test on purpose: ShelfCore keeps its preferences for the life of the process, so state
	// from a second test here would leak into the first.
	@Test
	fun `the timer is set once per exposure and dropped when nothing is exposed`() {
		val context: Application = ApplicationProvider.getApplicationContext()
		ShelfCore.install(context)
		val preferences = ShelfCore.preferences
		val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
		val window = 15 * 60_000L

		preferences.setRehideMinutes(0)
		RehideTimer.sync(context, exposed = true)
		assertNull(alarms.nextScheduledAlarm)

		preferences.setRehideMinutes(15)
		RehideTimer.sync(context, exposed = true)
		val first = preferences.rehideAt()
		assertEquals(SystemClock.elapsedRealtime() + window, first)
		assertEquals(first, alarms.nextScheduledAlarm?.triggerAtTime)

		// A second folder coming out does not buy the first one more time.
		SystemClock.setCurrentTimeMillis(SystemClock.elapsedRealtime() + 60_000L)
		RehideTimer.sync(context, exposed = true)
		assertEquals(first, preferences.rehideAt())

		RehideTimer.sync(context, exposed = false)
		assertEquals(0L, preferences.rehideAt())
		assertNull(alarms.nextScheduledAlarm)

		RehideTimer.reschedule(context, exposed = true)
		assertEquals(SystemClock.elapsedRealtime() + window, preferences.rehideAt())
		preferences.setRehideMinutes(0)
		RehideTimer.sync(context, exposed = true)
		assertNull(alarms.nextScheduledAlarm)
	}
}
