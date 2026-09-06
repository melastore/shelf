package io.github.melastore.shelf.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.AppPreferences
import io.github.melastore.shelf.security.CredentialKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AuthCoordinatorTest {

	@get:Rule val temp = TemporaryFolder()

	private val context: Application get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `matches primary, decoy, and burner credentials distinctly`() = runBlocking {
		val coordinator = AuthCoordinator(temp.root, AppPreferences(context))
		val primary = "1234".toCharArray()
		val decoy = "5678".toCharArray()
		val burner = "9999".toCharArray()
		val wrong = "0000".toCharArray()

		assertFalse(coordinator.primaryIsSet())
		assertFalse(coordinator.decoyIsSet())
		assertFalse(coordinator.burnerIsSet())

		coordinator.setPrimary(primary, CredentialKind.PIN)
		coordinator.setDecoy(decoy)
		coordinator.setBurner(burner)

		assertTrue(coordinator.primaryIsSet())
		assertTrue(coordinator.decoyIsSet())
		assertTrue(coordinator.burnerIsSet())

		assertEquals(CredentialMatch.PRIMARY, coordinator.match(primary))
		assertEquals(CredentialMatch.DECOY, coordinator.match(decoy))
		assertEquals(CredentialMatch.BURNER, coordinator.match(burner))
		assertEquals(CredentialMatch.NONE, coordinator.match(wrong))

		// Clear burner PIN
		coordinator.clearBurner()
		assertFalse(coordinator.burnerIsSet())
		assertEquals(CredentialMatch.NONE, coordinator.match(burner))
		assertEquals(CredentialMatch.PRIMARY, coordinator.match(primary))
	}
}
