package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContentKeysTest {

	private lateinit var application: Application

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().clear().commit()
	}

	@Test
	fun `each salt is made once and kept`() {
		val header = ContentKeys.headerSalt(application)
		val manifest = ContentKeys.manifestSalt(application)

		assertEquals(16, header.size)
		assertArrayEquals(header, ContentKeys.headerSalt(application))
		assertArrayEquals(manifest, ContentKeys.manifestSalt(application))
		assertFalse(header.contentEquals(manifest))
	}

	@Test
	fun `a damaged salt is replaced rather than used`() {
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit()
			.putString("salt_header", "not base64!").commit()

		assertEquals(16, ContentKeys.headerSalt(application).size)
	}
}
