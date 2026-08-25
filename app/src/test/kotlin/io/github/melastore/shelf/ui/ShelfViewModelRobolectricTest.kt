package io.github.melastore.shelf.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.security.CredentialKind
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ShelfViewModelRobolectricTest {

	private lateinit var application: Application

	@Before
	fun setUp() {
		application = ApplicationProvider.getApplicationContext()
		application.filesDir.listFiles()?.forEach { it.deleteRecursively() }
		application.getSharedPreferences("settings", Application.MODE_PRIVATE).edit().clear().commit()
		application.getSharedPreferences("biometric_credential", Application.MODE_PRIVATE)
			.edit().clear().commit()
		ContentCredential.clear()
	}

	@Test
	fun `biometric unlock supplies the credential needed by protected folders`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()
			var rejected = false

			viewModel.unlockWithBiometric("4826".toCharArray()) { rejected = true }

			await { viewModel.state.value.screen == Screen.VAULT || rejected }
			assertTrue("biometric credential was rejected", !rejected)
			val credential = requireNotNull(ContentCredential.copy())
			assertArrayEquals("4826".toCharArray(), credential)
			assertTrue(!rejected)
			credential.fill(' ')
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `correct PIN reaches the vault through the ViewModel unlock path`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()

			viewModel.unlockVault("4826".toCharArray())

			await { viewModel.state.value.screen == Screen.VAULT }
			assertEquals(Screen.VAULT, viewModel.state.value.screen)
			assertTrue(viewModel.state.value.credentialSet)
		} finally {
			store.clear()
		}
	}

	@Test
	fun `immediate auto hide closes the private space on background`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.setAutoHideMode(AutoHideMode.IMMEDIATE)
			await { viewModel.state.value.autoHideMode == AutoHideMode.IMMEDIATE }

			viewModel.onMovedToBackground()

			await { viewModel.state.value.screen == Screen.DECOY }
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `allowing screenshots lasts only until the private space closes`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "7391".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			assertTrue("protection should start armed", !viewModel.state.value.allowScreenshots)

			viewModel.setAllowScreenshots(true)
			assertTrue("the toggle should take effect", viewModel.state.value.allowScreenshots)

			viewModel.lockVault()

			assertTrue("locking must re-arm protection", !viewModel.state.value.allowScreenshots)

			viewModel.unlockWithBiometric("7391".toCharArray()) {}
			await { viewModel.state.value.screen == Screen.VAULT }
			assertTrue(
				"reopening must not restore the exemption",
				!viewModel.state.value.allowScreenshots,
			)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	/**
	 * A pattern and a password have to open the space and reach the folder machinery exactly as a PIN
	 * does: the credential is also the key file headers are protected with, so a kind that unlocked the
	 * UI but arrived at the folder layer in some other shape would leave every protected folder shut.
	 */
	@Test
	fun `a pattern opens the space and reaches the folder machinery`() {
		assertCredentialOpensVault(CredentialKind.PATTERN, "0481")
	}

	@Test
	fun `a password opens the space and reaches the folder machinery`() {
		assertCredentialOpensVault(CredentialKind.PASSWORD, "correct horse battery")
	}

	@Test
	fun `a password outside the ascii range survives being set and re-entered`() {
		assertCredentialOpensVault(CredentialKind.PASSWORD, "ሚስጥር ቁልፍ")
	}

	private fun assertCredentialOpensVault(kind: CredentialKind, secret: String) {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(kind, secret.toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			assertEquals(kind, viewModel.state.value.credentialKind)

			viewModel.lockVault()
			assertEquals(Screen.DECOY, viewModel.state.value.screen)

			viewModel.unlockVault(secret.toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			val credential = requireNotNull(ContentCredential.copy())
			assertArrayEquals(secret.toCharArray(), credential)
			credential.fill(' ')
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	/**
	 * The second credential is entered through the same prompt as the first. Changing the kind leaves
	 * one that prompt cannot express, so it goes rather than staying as a credential that never works.
	 */
	@Test
	fun `changing the credential kind drops a second credential it could not enter`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			viewModel.setDecoyCredential("4826".toCharArray(), "9999".toCharArray())
			await { viewModel.state.value.decoyPinSet }

			viewModel.changeVaultCredential(
				CredentialKind.PATTERN,
				"4826".toCharArray(),
				"0481".toCharArray(),
			)
			await { viewModel.state.value.credentialKind == CredentialKind.PATTERN }

			assertTrue("the unusable second credential should be gone", !viewModel.state.value.decoyPinSet)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `a credential of the wrong kind does not open the space`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PATTERN, "0481".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()

			viewModel.unlockVault("1840".toCharArray())
			await { viewModel.state.value.message != null }

			assertEquals(Screen.DECOY, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	private fun await(condition: () -> Boolean) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
		while (!condition() && System.nanoTime() < deadline) {
			shadowOf(Looper.getMainLooper()).idle()
			Thread.sleep(10)
		}
		shadowOf(Looper.getMainLooper()).idle()
		assertTrue("condition was not reached before timeout", condition())
	}
}
