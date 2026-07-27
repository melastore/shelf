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

	@Test
	fun `full recovery lets each strategy perform its own availability check`() = runBlocking {
		val root = FakeStrategy(HideMethod.ROOT_CHMOD, recovered = 2)
		val saf = FakeStrategy(HideMethod.DOT_RENAME, recovered = 1)

		val recovered = FolderHider(listOf(root, saf)).recoverEverything()

		assertEquals(3, recovered)
		assertEquals(0, root.availabilityChecks)
		assertEquals(1, root.recoveryAttempts)
		assertEquals(1, saf.recoveryAttempts)
	}

	@Test
	fun `health check is routed to the method that hid the entry`() = runBlocking {
		val root = FakeStrategy(HideMethod.ROOT_CHMOD)
		val saf = FakeStrategy(HideMethod.DOT_RENAME)
		val entry = HiddenEntry("/folder", "folder", 1, HideMethod.DOT_RENAME)

		val health = FolderHider(listOf(root, saf)).health(entry)

		assertEquals(HiddenHealthStatus.HEALTHY, health.status)
		assertEquals(0, root.healthChecks)
		assertEquals(1, saf.healthChecks)
	}

	@Test
	fun `interrupted journal entry is rolled back before notification retries hide`() = runBlocking {
		val strategy = FakeStrategy(
			HideMethod.DOT_RENAME,
			health = HiddenHealth(
				HiddenHealthStatus.ALREADY_RESTORED,
				HiddenHealthDetail.RENAME_ALREADY_RESTORED,
			),
		)
		val hider = FolderHider(listOf(strategy))
		val entry = HiddenEntry("/folder", "folder", 1, HideMethod.DOT_RENAME)

		val result = hider.rehide(
			FolderTarget("/folder", "folder", null),
			HidingPreference.SAF,
			entry,
		)

		assertTrue(result is HideResult.Ok)
		assertEquals(listOf("health", "restore", "hide"), strategy.calls)
	}

	@Test
	fun `physical state overrides a stale hidden journal record`() = runBlocking {
		val exposed = FakeStrategy(
			HideMethod.PRIVATE_MOVE,
			health = HiddenHealth(
				HiddenHealthStatus.ALREADY_RESTORED,
				HiddenHealthDetail.MOVE_ALREADY_RESTORED,
			),
		)
		val hidden = FakeStrategy(HideMethod.DOT_RENAME)
		val hider = FolderHider(listOf(exposed, hidden))

		assertTrue(hider.isExposed(HiddenEntry("/one", "one", 1, HideMethod.PRIVATE_MOVE)))
		assertFalse(hider.isExposed(HiddenEntry("/two", "two", 1, HideMethod.DOT_RENAME)))
	}

	private class FakeStrategy(
		override val method: HideMethod,
		private val recovered: Int = 0,
		private val health: HiddenHealth =
			HiddenHealth(HiddenHealthStatus.HEALTHY, HiddenHealthDetail.NOT_SUPPORTED),
	) : HideStrategy {
		var availabilityChecks = 0
		var recoveryAttempts = 0
		var healthChecks = 0
		val calls = mutableListOf<String>()

		override suspend fun isAvailable(): Boolean {
			availabilityChecks++
			return true
		}

		override suspend fun hide(target: FolderTarget): HideResult {
			calls += "hide"
			return HideResult.Ok(HiddenEntry(target.emulatedPath, target.displayName, 1, method))
		}

		override suspend fun restore(entry: HiddenEntry): HideResult {
			calls += "restore"
			return HideResult.Ok(entry)
		}

		override suspend fun recoverOrphans(): Int {
			recoveryAttempts++
			return recovered
		}

		override suspend fun health(entry: HiddenEntry): HiddenHealth {
			healthChecks++
			calls += "health"
			return health
		}
	}
}
