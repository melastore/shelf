package io.github.melastore.shelf.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmergencyCredentialStoreTest {

	private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Before fun clearBefore() = EmergencyCredentialStore.clear(context)

	@After fun clearAfter() = EmergencyCredentialStore.clear(context)

	@Test
	fun deviceBoundCredentialExistsOnlyUntilCleared() {
		assertTrue(EmergencyCredentialStore.arm(context, "4826".toCharArray()))
		val restored = requireNotNull(EmergencyCredentialStore.load(context))
		try {
			assertArrayEquals("4826".toCharArray(), restored)
		} finally {
			restored.fill(' ')
		}

		EmergencyCredentialStore.clear(context)
		assertNull(EmergencyCredentialStore.load(context))
	}
}
