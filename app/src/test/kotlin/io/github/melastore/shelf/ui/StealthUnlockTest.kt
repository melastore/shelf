package io.github.melastore.shelf.ui

import io.github.melastore.shelf.data.CalculatorOperation
import io.github.melastore.shelf.data.CalculatorState
import org.junit.Assert.assertEquals
import org.junit.Test

class StealthUnlockTest {

	@Test
	fun `a sum offers its result as well as a pin shaped operand`() {
		val evaluated = CalculatorState()
			.digits("1250")
			.operator(CalculatorOperation.ADD)
			.digits("1250")
			.evaluate()

		val candidates = stealthUnlock(emptyList(), typed = "1250", lastOperand = "1250", evaluated)

		assertEquals(listOf("1250", "2500"), candidates.map(::String))
	}

	@Test
	fun `pin then equals is tried once`() {
		val evaluated = CalculatorState().digits("4826").evaluate()

		val candidates = stealthUnlock(emptyList(), typed = "4826", lastOperand = "", evaluated)

		assertEquals(listOf("4826"), candidates.map(::String))
	}

	@Test
	fun `short sums offer nothing`() {
		val evaluated = CalculatorState().digits("12").operator(CalculatorOperation.ADD).digits("7").evaluate()

		assertEquals(emptyList<String>(), stealthUnlock(emptyList(), "7", "12", evaluated).map(::String))
	}

	private fun CalculatorState.digits(value: String): CalculatorState =
		value.fold(this) { state, digit -> state.digit(digit - '0') }
}
