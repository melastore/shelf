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
class AppPreferencesAutoHideTest {

	private lateinit var application: Application

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().clear().commit()
	}

	@Test
	fun `new installs default to after screen off`() {
		assertEquals(AutoHideMode.SCREEN_OFF, AppPreferences(application).autoHideMode())
	}

	@Test
	fun `legacy immediate background lock migrates to immediate auto hide`() {
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit()
			.putString("lock_timeout", "IMMEDIATE")
			.putString("lock_trigger", "NEVER")
			.commit()

		assertEquals(AutoHideMode.IMMEDIATE, AppPreferences(application).autoHideMode())
	}

	@Test
	fun `legacy screen off trigger migrates to after screen off`() {
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit()
			.putString("lock_timeout", "NEVER")
			.putString("lock_trigger", "SCREEN_OFF")
			.commit()

		assertEquals(AutoHideMode.SCREEN_OFF, AppPreferences(application).autoHideMode())
	}

	@Test
	fun `legacy never settings remain never`() {
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit()
			.putString("lock_timeout", "NEVER")
			.putString("lock_trigger", "NEVER")
			.commit()

		assertEquals(AutoHideMode.NEVER, AppPreferences(application).autoHideMode())
	}

	@Test
	fun `explicit never selection persists`() {
		val preferences = AppPreferences(application)
		preferences.setAutoHideMode(AutoHideMode.NEVER)

		assertEquals(AutoHideMode.NEVER, AppPreferences(application).autoHideMode())
	}

	@Test
	fun `direct keypad entry method persists`() {
		val preferences = AppPreferences(application)
		preferences.setEntryMethod(EntryMethod.DIRECT_KEYPAD)

		assertEquals(EntryMethod.DIRECT_KEYPAD, preferences.entryMethod())
	}

	@Test
	fun `double tap title entry method persists`() {
		val preferences = AppPreferences(application)
		preferences.setEntryMethod(EntryMethod.DOUBLE_TAP_TITLE)

		assertEquals(EntryMethod.DOUBLE_TAP_TITLE, preferences.entryMethod())
	}
}
