package io.github.melastore.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryNudgeTest {

	private fun set(vararg names: String) = names.toSet()

	@Test
	fun `nothing hidden asks for nothing`() {
		assertFalse(RecoveryNudge.due(emptySet(), null, null))
	}

	@Test
	fun `the first hide asks once, and not now holds until more is hidden`() {
		assertTrue(RecoveryNudge.due(set("a"), null, null))
		assertFalse(RecoveryNudge.due(set("a"), null, set("a")))
		assertTrue(RecoveryNudge.due(set("a", "b"), null, set("a")))
	}

	@Test
	fun `a few new folders on a long list do not ask, three do`() {
		val exported = (1..10).map { "f$it" }.toSet()
		assertFalse(RecoveryNudge.due(exported + "n1", exported, null))
		assertFalse(RecoveryNudge.due(exported + set("n1", "n2"), exported, null))
		assertTrue(RecoveryNudge.due(exported + set("n1", "n2", "n3"), exported, null))
	}

	@Test
	fun `half the list new asks even when it is short`() {
		assertTrue(RecoveryNudge.due(set("a", "b"), set("a"), null))
		assertFalse(RecoveryNudge.due(set("a", "b", "c"), set("a", "b"), null))
	}

	@Test
	fun `unhiding never asks`() {
		assertFalse(RecoveryNudge.due(set("a"), set("a", "b", "c"), null))
	}

	@Test
	fun `digests depend on the salt and fit in a short string`() {
		val one = RecoveryNudge.digest(ByteArray(16) { 1 }, "/storage/emulated/0/DCIM/x")
		val two = RecoveryNudge.digest(ByteArray(16) { 2 }, "/storage/emulated/0/DCIM/x")
		assertNotEquals(one, two)
		assertEquals(16, one.length)
		assertEquals(one, RecoveryNudge.digest(ByteArray(16) { 1 }, "/storage/emulated/0/DCIM/x"))
	}
}
