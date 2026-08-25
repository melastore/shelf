package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.security.CredentialKind
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesCredentialKindTest {

	private lateinit var application: Application

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		settings().edit().clear().commit()
	}

	@Test
	fun `a fresh install starts on a pin`() {
		assertEquals(CredentialKind.PIN, AppPreferences(application).credentialKind(vaultCredentialExists = false))
	}

	/**
	 * The oldest installs have no setting at all: the only thing that says which credential they use is
	 * that they have one, and on those builds it could only have been a passphrase. Reading them as a
	 * PIN would put a keypad in front of an owner whose credential has letters in it.
	 */
	@Test
	fun `an install that predates the setting keeps its passphrase`() {
		assertEquals(
			CredentialKind.PASSWORD,
			AppPreferences(application).credentialKind(vaultCredentialExists = true),
		)
	}

	@Test
	fun `the older pin flag still decides on installs that have one`() {
		settings().edit().putBoolean("vault_uses_pin", true).commit()
		assertEquals(CredentialKind.PIN, AppPreferences(application).credentialKind(vaultCredentialExists = true))

		settings().edit().putBoolean("vault_uses_pin", false).commit()
		assertEquals(CredentialKind.PASSWORD, AppPreferences(application).credentialKind(vaultCredentialExists = true))
	}

	@Test
	fun `a stored kind outranks both older keys`() {
		settings().edit().putBoolean("vault_uses_pin", true).commit()
		AppPreferences(application).setCredentialKind(CredentialKind.PATTERN)

		assertEquals(CredentialKind.PATTERN, AppPreferences(application).credentialKind(vaultCredentialExists = true))
	}

	/** A build that only knows the older flag has to keep working after a downgrade. */
	@Test
	fun `setting a kind keeps the older flag in step`() {
		AppPreferences(application).setCredentialKind(CredentialKind.PASSWORD)
		assertEquals(false, settings().getBoolean("vault_uses_pin", true))

		AppPreferences(application).setCredentialKind(CredentialKind.PATTERN)
		assertEquals(true, settings().getBoolean("vault_uses_pin", false))
	}

	@Test
	fun `an unreadable stored kind falls back to a pin`() {
		settings().edit().putString("credential_kind", "SEMAPHORE").commit()
		assertEquals(CredentialKind.PIN, AppPreferences(application).credentialKind(vaultCredentialExists = true))
	}

	private fun settings() = application.getSharedPreferences("settings", Application.MODE_PRIVATE)
}
