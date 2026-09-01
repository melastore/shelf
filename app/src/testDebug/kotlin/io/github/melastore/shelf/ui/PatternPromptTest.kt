package io.github.melastore.shelf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.PatternCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drawing half of the pattern prompt. [PatternCode] covers the arithmetic; this covers the part
 * between a finger and that arithmetic, which is where a pattern the owner draws every day and a
 * pattern Shelf recorded once can quietly stop being the same thing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class PatternPromptTest {

	@get:Rule
	val compose = createComposeRule()

	@Test
	fun `dragging across the grid records the dots in the order they were touched`() {
		val drawn = promptFor { drag(0, 1, 2, 5) }

		assertEquals("0125", drawn)
	}

	/**
	 * The dot the finger goes down on is part of the pattern. Reading the position after touch slop
	 * has been consumed drops it on any quick swipe, and the owner is refused their own pattern.
	 */
	@Test
	fun `the dot the drag started on is not lost to touch slop`() {
		val drawn = promptFor { drag(4, 5, 8, 7) }

		assertEquals("4587", drawn)
	}

	/** Android's pattern lock collects a dot a line passes over, and so must this one. */
	@Test
	fun `a line straight across a row collects the dot in the middle`() {
		assertEquals("0123", promptFor { drag(0, 2, 3) })
	}

	@Test
	fun `a line across the diagonal collects the centre`() {
		assertEquals("0483", promptFor { drag(0, 8, 3) })
	}

	@Test
	fun `a dot already used is not collected twice`() {
		val drawn = promptFor { drag(0, 1, 2, 1, 4) }

		assertEquals("0124", drawn)
	}

	@Test
	fun `what the grid produces is something the rules accept`() {
		val drawn = promptFor { drag(0, 4, 8, 5) }

		assertNull(CredentialRules.validate(CredentialKind.PATTERN, drawn.toCharArray()))
		assertTrue(PatternCode.isEncoded(drawn.toCharArray()))
	}

	@Test
	fun `too few dots leaves the prompt unable to confirm`() {
		var confirmed: String? = null
		compose.setContent {
			ShelfTheme(decoy = null) {
				PatternPrompt(
					title = "Draw",
					subtitle = "",
					confirmLabel = CONFIRM,
					onConfirm = { confirmed = it.concatToString() },
					onDismiss = {},
				)
			}
		}

		compose.onNodeWithTag(PATTERN).performTouchInput { swipeThrough(this, listOf(0, 1)) }
		compose.onNodeWithText(CONFIRM).performClick()
		compose.waitForIdle()

		assertNull(confirmed)
	}

	/** Setting a credential asks twice, and the second drawing has to be compared with the first. */
	@Test
	fun `a confirming prompt only hands over a pattern drawn the same way twice`() {
		var confirmed: String? = null
		compose.setContent {
			ShelfTheme(decoy = null) {
				PatternPrompt(
					title = "Draw",
					subtitle = "",
					confirmLabel = CONFIRM,
					confirmEntry = true,
					onConfirm = { confirmed = it.concatToString() },
					onDismiss = {},
				)
			}
		}

		draw(listOf(0, 1, 2, 5))
		compose.onNodeWithText(CONTINUE).performClick()
		compose.waitForIdle()

		draw(listOf(0, 3, 6, 7))
		compose.onNodeWithText(CONFIRM).performClick()
		compose.waitForIdle()
		assertNull("a different pattern must not be accepted", confirmed)

		draw(listOf(0, 1, 2, 5))
		compose.onNodeWithText(CONTINUE).performClick()
		compose.waitForIdle()
		draw(listOf(0, 1, 2, 5))
		compose.onNodeWithText(CONFIRM).performClick()
		compose.waitForIdle()

		assertEquals("0125", confirmed)
	}

	private fun promptFor(draw: PatternPromptTest.() -> Unit): String {
		var drawn = ""
		compose.setContent {
			ShelfTheme(decoy = null) {
				PatternPrompt(
					title = "Draw",
					subtitle = "",
					confirmLabel = CONFIRM,
					onConfirm = { drawn = it.concatToString() },
					onDismiss = {},
				)
			}
		}
		draw()
		compose.onNodeWithText(CONFIRM).performClick()
		compose.waitForIdle()
		return drawn
	}

	private fun drag(vararg dots: Int) = draw(dots.toList())

	private fun draw(dots: List<Int>) {
		compose.onNodeWithTag(PATTERN).performTouchInput { swipeThrough(this, dots) }
		compose.waitForIdle()
	}

	private fun swipeThrough(scope: androidx.compose.ui.test.TouchInjectionScope, dots: List<Int>) {
		val spacing = minOf(scope.width, scope.height) / PatternCode.SIDE.toFloat()
		fun centre(dot: Int) = Offset(
			spacing / 2f + dot % PatternCode.SIDE * spacing,
			spacing / 2f + dot / PatternCode.SIDE * spacing,
		)
		scope.down(centre(dots.first()))
		dots.drop(1).forEach { scope.moveTo(centre(it)) }
		scope.up()
	}

	private companion object {
		const val PATTERN = "pattern"
		const val CONFIRM = "Save"
		const val CONTINUE = "Continue"
	}
}
