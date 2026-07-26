package io.github.melastore.shelf.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderHiderTest {

	@Test
	fun `quiet capability check does not invoke root`() = runBlocking {
		val root = FakeStrategy(HideMethod.ROOT_CHMOD)
		val saf = FakeStrategy(HideMethod.DOT_RENAME)
		val hider = FolderHider(listOf(root, saf))

		val available = hider.availableMethods(checkRoot = false)

		assertFalse(HideMethod.ROOT_CHMOD in available)
		assertTrue(HideMethod.DOT_RENAME in available)
		assertEquals(0, root.availabilityChecks)
		assertEquals(1, saf.availabilityChecks)
	}

	@Test
	fun `explicit unavailable method does not silently fall back`() {
		val hider = FolderHider(emptyList())

		assertNull(hider.selectedMethod(HidingPreference.ROOT, setOf(HideMethod.DOT_RENAME)))
		assertEquals(
			HideMethod.DOT_RENAME,
			hider.selectedMethod(HidingPreference.SAF, setOf(HideMethod.DOT_RENAME)),
		)
	}

	private class FakeStrategy(override val method: HideMethod) : HideStrategy {
		var availabilityChecks = 0

		override suspend fun isAvailable(): Boolean {
			availabilityChecks++
			return true
		}

		override suspend fun hide(target: FolderTarget): HideResult = error("not used")

		override suspend fun restore(entry: HiddenEntry): HideResult = error("not used")
	}
}
