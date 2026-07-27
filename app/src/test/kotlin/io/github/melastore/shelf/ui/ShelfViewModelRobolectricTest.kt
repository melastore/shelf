package io.github.melastore.shelf.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.ContentCredential
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
			viewModel.setVaultPin("4826".toCharArray())
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
			viewModel.setVaultPin("4826".toCharArray())
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
			viewModel.setVaultPin("4826".toCharArray())
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
			viewModel.setVaultPin("7391".toCharArray())
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
