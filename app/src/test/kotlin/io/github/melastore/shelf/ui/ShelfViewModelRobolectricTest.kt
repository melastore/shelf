package io.github.melastore.shelf.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.HiddenNameStyle
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.KnockCode
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
	fun `a knock code typed one-based opens the vault under its stored spelling`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.KNOCK, KnockCode.encode(listOf(0, 0, 3, 3)))
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()
			await { viewModel.state.value.screen != Screen.VAULT }
			ContentCredential.clear()

			// The quarters counted one to four, which is what a calculator can actually show.
			viewModel.tryStealthUnlock(listOf("1144".toCharArray()))
			await { viewModel.state.value.screen == Screen.VAULT }

			// The folders were sealed under the zero-based form, so that is what has to be held.
			// Keeping the keys that were pressed would open the vault and then fail every folder.
			assertEquals("0033", ContentCredential.copy()?.concatToString())
		} finally {
			store.clear()
		}
	}

	@Test
	fun `a wrong one-based code opens nothing`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.KNOCK, KnockCode.encode(listOf(0, 0, 3, 3)))
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()
			await { viewModel.state.value.screen != Screen.VAULT }

			viewModel.tryStealthUnlock(listOf("1244".toCharArray()))

			shadowOf(Looper.getMainLooper()).idle()
			assertTrue(viewModel.state.value.screen != Screen.VAULT)
		} finally {
			store.clear()
		}
	}

	@Test
	fun `a knock credential opens from the calculator keypad as digits`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			// Top-left, top-left, bottom-right, bottom-right.
			viewModel.setVaultCredential(CredentialKind.KNOCK, KnockCode.encode(listOf(0, 0, 3, 3)))
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.lockVault()
			await { viewModel.state.value.screen != Screen.VAULT }

			// The same quarters typed on the keys. A knock code is stored as the digits of the
			// quarters it taps, so the keypad reaches it without knowing it is a knock code at all.
			viewModel.tryStealthUnlock(listOf("0033".toCharArray()))

			await { viewModel.state.value.screen == Screen.VAULT }
		} finally {
			store.clear()
		}
	}

	@Test
	fun `a credential the calculator keypad cannot take moves the entrance off the keypad`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setEntryMethod(EntryMethod.DIRECT_KEYPAD)
			await { viewModel.state.value.entryMethod == EntryMethod.DIRECT_KEYPAD }

			viewModel.setVaultCredential(CredentialKind.PASSWORD, "a real password".toCharArray())
			// The vault opens after the guard runs, so this is the barrier, not credentialSet.
			await { viewModel.state.value.screen == Screen.VAULT }

			// The keypad takes digits and takes the title gesture and corner taps away with it, so a
			// password left there is a vault with no entrance at all.
			assertEquals(EntryMethod.DOUBLE_TAP_TITLE, viewModel.state.value.entryMethod)
		} finally {
			store.clear()
		}
	}

	@Test
	fun `a PIN is left on the keypad entrance`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setEntryMethod(EntryMethod.DIRECT_KEYPAD)
			await { viewModel.state.value.entryMethod == EntryMethod.DIRECT_KEYPAD }

			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			assertEquals(EntryMethod.DIRECT_KEYPAD, viewModel.state.value.entryMethod)
		} finally {
			store.clear()
		}
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
	fun `auto hide after screen off closes the private space`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.setAutoHideMode(AutoHideMode.SCREEN_OFF)
			await { viewModel.state.value.autoHideMode == AutoHideMode.SCREEN_OFF }

			viewModel.onScreenTurnedOff()

			await { viewModel.state.value.screen == Screen.DECOY }
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `moving to background when screen is off closes the private space in screen off mode`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.setAutoHideMode(AutoHideMode.SCREEN_OFF)
			await { viewModel.state.value.autoHideMode == AutoHideMode.SCREEN_OFF }

			viewModel.onMovedToBackground(screenOff = true)

			await { viewModel.state.value.screen == Screen.DECOY }
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `moving to background when screen is on keeps the private space open in screen off mode`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.setAutoHideMode(AutoHideMode.SCREEN_OFF)
			await { viewModel.state.value.autoHideMode == AutoHideMode.SCREEN_OFF }

			viewModel.onMovedToBackground(screenOff = false)

			assertEquals(Screen.VAULT, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `flip to hide is disabled by default`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			assertFalse(viewModel.state.value.flipToHide)
		} finally {
			store.clear()
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

			viewModel.setBurnerPin("4826".toCharArray(), "1234".toCharArray())
			await { viewModel.state.value.burnerPinSet }

			viewModel.changeVaultCredential(
				CredentialKind.PATTERN,
				"4826".toCharArray(),
				"0481".toCharArray(),
			)
			await { viewModel.state.value.credentialKind == CredentialKind.PATTERN }

			assertTrue("the unusable second credential should be gone", !viewModel.state.value.decoyPinSet)
			assertTrue("the unusable burner credential should be gone", !viewModel.state.value.burnerPinSet)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `burner and decoy and primary credentials must be mutually exclusive`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			// Setting decoy PIN matching primary PIN fails
			viewModel.setDecoyCredential("4826".toCharArray(), "4826".toCharArray())
			await { viewModel.state.value.message != null }
			assertFalse(viewModel.state.value.decoyPinSet)
			viewModel.consumeMessage()

			// Setting decoy PIN to 9999 succeeds
			viewModel.setDecoyCredential("4826".toCharArray(), "9999".toCharArray())
			await { viewModel.state.value.decoyPinSet }
			viewModel.consumeMessage()

			// Setting burner PIN matching decoy PIN (9999) fails
			viewModel.setBurnerPin("4826".toCharArray(), "9999".toCharArray())
			await { viewModel.state.value.message != null }
			assertFalse(viewModel.state.value.burnerPinSet)
			viewModel.consumeMessage()

			// Setting burner PIN matching primary PIN (4826) fails
			viewModel.setBurnerPin("4826".toCharArray(), "4826".toCharArray())
			await { viewModel.state.value.message != null }
			assertFalse(viewModel.state.value.burnerPinSet)
			viewModel.consumeMessage()

			// Setting burner PIN to distinct 1234 succeeds
			viewModel.setBurnerPin("4826".toCharArray(), "1234".toCharArray())
			await { viewModel.state.value.burnerPinSet }
			viewModel.consumeMessage()

			// Setting decoy PIN matching existing burner PIN (1234) fails
			viewModel.setDecoyCredential("4826".toCharArray(), "1234".toCharArray())
			await { viewModel.state.value.message != null }
			viewModel.consumeMessage()

			// Changing primary credential to match burner PIN (1234) fails
			viewModel.changeVaultCredential(CredentialKind.PIN, "4826".toCharArray(), "1234".toCharArray())
			await { viewModel.state.value.message != null }
			viewModel.consumeMessage()

			// Changing primary credential to match decoy PIN (9999) fails
			viewModel.changeVaultCredential(CredentialKind.PIN, "4826".toCharArray(), "9999".toCharArray())
			await { viewModel.state.value.message != null }
			viewModel.consumeMessage()

			// Changing primary credential to a distinct PIN (5555) succeeds
			viewModel.changeVaultCredential(CredentialKind.PIN, "4826".toCharArray(), "5555".toCharArray())
			await { viewModel.state.value.message != null }
			assertTrue(viewModel.state.value.decoyPinSet)
			assertTrue(viewModel.state.value.burnerPinSet)
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

	@Test
	fun `unlocking with burner PIN wipes everything and keeps the disguise up`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			viewModel.setBurnerPin("4826".toCharArray(), "1234".toCharArray())
			await { viewModel.state.value.burnerPinSet }
			viewModel.consumeMessage()

			viewModel.lockVault()
			await { viewModel.state.value.screen == Screen.DECOY }

			viewModel.unlockVault("1234".toCharArray())
			await { !viewModel.state.value.burnerPinSet }

			// Still set up, or the next frame is the setup wizard naming the app.
			assertTrue(viewModel.state.value.credentialSet)
			assertFalse(viewModel.state.value.decoyPinSet)
			assertFalse(viewModel.state.value.biometricEnabled)
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
			assertTrue(viewModel.state.value.message != null)
			assertFalse(File(application.filesDir, "burner_gate").exists())
			assertFalse(File(application.filesDir, "journal.json").exists())
			viewModel.consumeMessage()

			// Nothing opens it any more, the old PIN included.
			viewModel.unlockVault("4826".toCharArray())
			await { viewModel.state.value.message != null }
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
			viewModel.unlockVault("1234".toCharArray())
			shadowOf(Looper.getMainLooper()).idle()
			assertEquals(Screen.DECOY, viewModel.state.value.screen)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `a folder picked under duress becomes a row and nothing else`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			viewModel.setDecoyCredential("4826".toCharArray(), "5555".toCharArray())
			await { viewModel.state.value.decoyPinSet }
			viewModel.lockVault()
			await { viewModel.state.value.screen == Screen.DECOY }

			viewModel.unlockVault("5555".toCharArray())
			await { viewModel.state.value.duress && viewModel.state.value.decoyItems.isNotEmpty() }
			val before = viewModel.state.value.decoyItems.size
			// Shared across tests in this process, so compared rather than expected empty.
			val tracked = runBlocking { ShelfCore.registry.read() }

			val tree = android.provider.DocumentsContract.buildTreeDocumentUri(
				"com.android.externalstorage.documents",
				"primary:Holiday",
			)
			viewModel.hideFolder(tree)
			await { viewModel.state.value.decoyItems.size == before + 1 }

			assertTrue(viewModel.state.value.decoyItems.any { it.name == "Holiday" && it.hidden })
			// No record in the real space, and no grant taken.
			assertEquals(tracked, runBlocking { ShelfCore.registry.read() })
			assertTrue(application.contentResolver.persistedUriPermissions.isEmpty())
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	/**
	 * Read back through a second view model, not through preferences: the factory these tests share
	 * hands every view model the application from the first test in the class.
	 */
	@Test
	fun `the hidden name style is camouflage until it is changed, and then it sticks`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			assertEquals(HiddenNameStyle.CAMOUFLAGE, viewModel.state.value.hiddenNameStyle)

			viewModel.setHiddenNameStyle(HiddenNameStyle.KEEP)
			await { viewModel.state.value.hiddenNameStyle == HiddenNameStyle.KEEP }
			assertEquals(null, viewModel.state.value.blocked)

			val reopened = ViewModelProvider(
				ViewModelStore(),
				ViewModelProvider.AndroidViewModelFactory.getInstance(application),
			)[ShelfViewModel::class.java]
			await { reopened.state.value.ready }
			assertEquals(HiddenNameStyle.KEEP, reopened.state.value.hiddenNameStyle)

			viewModel.setHiddenNameStyle(HiddenNameStyle.CAMOUFLAGE)
			await { viewModel.state.value.hiddenNameStyle == HiddenNameStyle.CAMOUFLAGE }
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	// A top-level folder is no longer a dead end. The dot rename cannot reach it, so the in-place
	// strategy takes it, and that needs a grant on the folder itself rather than all-files access.
	@Test
	fun `hiding a top-level folder asks for a grant instead of reporting a failure`() {
		val store = ViewModelStore()
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }

			val emulatedRoot = ShelfCore.paths.emulatedRoot
			val folder = VaultFolder(path = "$emulatedRoot/11", displayName = "11", entry = null)
			viewModel.hideAgain(folder)

			await { viewModel.state.value.accessNeededFor != null }
			assertEquals(null, viewModel.state.value.blocked)
		} finally {
			store.clear()
			ContentCredential.clear()
		}
	}

	@Test
	fun `bulk hide failure sets blocked report with multiple problems and row blockers`() {
		val store = ViewModelStore()
		val emulatedRoot = ShelfCore.paths.emulatedRoot
		runBlocking {
			ShelfCore.registry.put("$emulatedRoot/11", "11")
			ShelfCore.registry.put("$emulatedRoot/22", "22")
		}
		val viewModel = ViewModelProvider(
			store,
			ViewModelProvider.AndroidViewModelFactory.getInstance(application),
		)[ShelfViewModel::class.java]
		try {
			await { viewModel.state.value.ready }
			viewModel.setVaultCredential(CredentialKind.PIN, "4826".toCharArray())
			await { viewModel.state.value.screen == Screen.VAULT }
			await { viewModel.state.value.folders.size == 2 }

			// The in-place strategy can take both, so neither row is marked unhideable.
			val folders = viewModel.state.value.folders
			assertTrue(folders.all { it.blocker == null })

			viewModel.hideAll()

			// One picker per folder would be a queue of dialogs, so a bulk pass reports instead.
			await { viewModel.state.value.blocked != null }
			val blocked = requireNotNull(viewModel.state.value.blocked)
			assertFalse(blocked.needsAllFiles)
			assertEquals(2, blocked.problems.size)
			assertEquals(listOf("11", "22"), blocked.problems.map { it.name })

			viewModel.dismissBlocked()
			await { viewModel.state.value.blocked == null }
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
