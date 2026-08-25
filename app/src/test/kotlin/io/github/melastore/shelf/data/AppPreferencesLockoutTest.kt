package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesLockoutTest {

	private lateinit var preferences: AppPreferences

	@Before
	fun setUp() {
		val application: Application = ApplicationProvider.getApplicationContext()
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().clear().commit()
		preferences = AppPreferences(application)
	}

	@Test
	fun `attempts below the limit do not block`() {
		repeat(ATTEMPTS - 1) { assertEquals(0L, preferences.recordFailedUnlock(NOW, ATTEMPTS)) }
		assertEquals(0L, preferences.blockedUntil(NOW))
	}

	@Test
	fun `the limit blocks for the first step of the ladder`() {
		assertEquals(NOW + 30_000L, lockOutOnce(NOW))
		assertEquals(NOW + 30_000L, preferences.blockedUntil(NOW))
	}

	/**
	 * A flat delay is a rate, and a rate is not a defence: at five attempts per thirty seconds every
	 * four-dot pattern falls in about three hours. Each lockout has to cost more than the last.
	 */
	@Test
	fun `each lockout costs more than the one before it`() {
		var now = NOW
		val waits = LOCKOUT_LADDER.indices.map {
			val until = lockOutOnce(now)
			val wait = until - now
			now = until
			wait
		}

		assertEquals(LOCKOUT_LADDER, waits)
	}

	@Test
	fun `the ladder holds at its longest step rather than wrapping`() {
		var now = NOW
		repeat(LOCKOUT_LADDER.size + 3) { now = lockOutOnce(now) }

		assertEquals(LOCKOUT_LADDER.last(), lockOutOnce(now) - now)
	}

	/** Backing off and coming back later must not hand the next attempt a fresh thirty seconds. */
	@Test
	fun `waiting out a lockout does not reset the ladder`() {
		val first = lockOutOnce(NOW)
		val second = lockOutOnce(first + 1)

		assertEquals(LOCKOUT_LADDER[1], second - (first + 1))
	}

	@Test
	fun `getting in clears the ladder`() {
		lockOutOnce(NOW)
		lockOutOnce(NOW)
		preferences.clearFailedUnlocks()

		assertEquals(0L, preferences.blockedUntil(NOW))
		assertEquals(NOW + LOCKOUT_LADDER.first(), lockOutOnce(NOW))
	}

	/**
	 * A reboot restarts the uptime clock, so a stored deadline can only ever be stale in the future.
	 * Honouring it would lock the owner out for an uptime the device no longer has.
	 */
	@Test
	fun `a deadline further away than the current step has survived a reboot`() {
		lockOutOnce(NOW)

		assertEquals(0L, preferences.blockedUntil(0L))
	}

	private fun lockOutOnce(now: Long): Long {
		repeat(ATTEMPTS - 1) { preferences.recordFailedUnlock(now, ATTEMPTS) }
		return preferences.recordFailedUnlock(now, ATTEMPTS)
	}

	private companion object {
		const val ATTEMPTS = 5
		const val NOW = 1_000_000L
	}
}
