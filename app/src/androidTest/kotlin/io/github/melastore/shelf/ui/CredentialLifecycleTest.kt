package io.github.melastore.shelf.ui

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.melastore.shelf.R
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fresh install opens the setup flow rather than a decoy: there is no credential to ask for yet,
 * and no reason to disguise an app that has nothing behind it.
 */
@RunWith(AndroidJUnit4::class)
class CredentialLifecycleTest {

	@get:Rule val compose = createAndroidComposeRule<MainActivity>()

	private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

	@Test
	fun firstRunOpensSetupAndKeepsTheWindowSecure() {
		compose.onNodeWithText(string(R.string.setup_welcome_title)).assertIsDisplayed()

		// Setup collects a PIN, so it is screenshot-protected for the same reason the vault is.
		compose.runOnIdle {
			assertNotEquals(
				0,
				compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
			)
		}
	}

	@Test
	fun setupWalksForwardAndBackWithoutLosingTheStep() {
		compose.onNodeWithText(string(R.string.setup_start)).performClick()
		compose.onNodeWithText(string(R.string.setup_disguise_title)).assertIsDisplayed()

		compose.onNodeWithText(string(R.string.continue_action)).performClick()
		compose.onNodeWithText(string(R.string.setup_entry_title)).assertIsDisplayed()

		compose.onNodeWithContentDescription(string(R.string.back)).performClick()
		compose.onNodeWithText(string(R.string.setup_disguise_title)).assertIsDisplayed()
	}
}
