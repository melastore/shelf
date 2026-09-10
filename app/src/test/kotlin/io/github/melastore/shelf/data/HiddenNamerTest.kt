package io.github.melastore.shelf.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenNamerTest {

	@Test
	fun `keeping the name dots it and ignores what is already there`() {
		val name = HiddenNamer.hiddenName(HiddenNameStyle.KEEP, "Holiday") { true }

		assertEquals(".Holiday", name)
		assertTrue(HiddenNamer.matches(HiddenNameStyle.KEEP, "Holiday", name))
	}

	@Test
	fun `camouflage never hands back the folder's own name`() {
		repeat(50) { seed ->
			val name = HiddenNamer.hiddenName(HiddenNameStyle.CAMOUFLAGE, "Holiday", Random(seed)) { false }

			assertFalse(name == ".Holiday")
			assertTrue(name.startsWith("."))
			assertTrue(HiddenNamer.matches(HiddenNameStyle.CAMOUFLAGE, "Holiday", name))
		}
	}

	@Test
	fun `a name already in the parent is passed over`() {
		val asked = mutableListOf<String>()
		val name = HiddenNamer.hiddenName(HiddenNameStyle.CAMOUFLAGE, "Holiday", Random(7)) { candidate ->
			asked += candidate
			asked.size == 1
		}

		assertEquals(2, asked.size)
		assertEquals(asked.last(), name)
	}

	@Test
	fun `random names are opaque and unique`() {
		val names = (1..20).map { seed ->
			HiddenNamer.hiddenName(HiddenNameStyle.RANDOM, "Holiday", Random(seed)) { false }
		}

		assertTrue(names.all { it.matches(Regex("\\.[0-9a-f]{8}")) })
		assertTrue(names.all { HiddenNamer.matches(HiddenNameStyle.RANDOM, "Holiday", it) })
		assertEquals(names.size, names.toSet().size)
	}

	/**
	 * A provider that errors on every probe reports every name as taken, and the fallback must still
	 * not be the readable name.
	 */
	@Test
	fun `a parent that claims to hold every name still gets a masked one`() {
		val name = HiddenNamer.hiddenName(HiddenNameStyle.CAMOUFLAGE, "Holiday", Random(3)) { true }

		assertFalse(name == ".Holiday")
		assertTrue(name.matches(Regex("\\.[0-9a-f]{8}")))
	}

	@Test
	fun `a name in one style does not pass for another`() {
		val camouflaged = HiddenNamer.hiddenName(HiddenNameStyle.CAMOUFLAGE, "Holiday", Random(1)) { false }
		val random = HiddenNamer.hiddenName(HiddenNameStyle.RANDOM, "Holiday", Random(1)) { false }

		assertFalse(HiddenNamer.matches(HiddenNameStyle.KEEP, "Holiday", camouflaged))
		assertFalse(HiddenNamer.matches(HiddenNameStyle.RANDOM, "Holiday", camouflaged))
		assertFalse(HiddenNamer.matches(HiddenNameStyle.CAMOUFLAGE, "Holiday", random))
		assertFalse(HiddenNamer.matches(HiddenNameStyle.CAMOUFLAGE, "Holiday", ".Holiday"))
	}
}
