package io.github.melastore.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorStateTest {

	@Test
	fun `enters whole and decimal numbers`() {
		val state = CalculatorState().digit(1).digit(2).decimal().digit(5)

		assertEquals("12.5", state.display)
	}

	@Test
	fun `calculates chained operations`() {
		val state = CalculatorState()
			.digit(8)
			.operator(CalculatorOperation.ADD)
			.digit(4)
			.operator(CalculatorOperation.MULTIPLY)
			.digit(3)
			.evaluate()

		assertEquals("36", state.display)
	}

	@Test
	fun `replaces an operator before another number is entered`() {
		val state = CalculatorState()
			.digit(9)
			.operator(CalculatorOperation.ADD)
			.operator(CalculatorOperation.SUBTRACT)
			.digit(2)
			.evaluate()

		assertEquals("7", state.display)
	}

	@Test
	fun `reports division by zero`() {
		val state = CalculatorState()
			.digit(4)
			.operator(CalculatorOperation.DIVIDE)
			.digit(0)
			.evaluate()

		assertEquals("Error", state.display)
	}

	@Test
	fun `supports sign and percent controls`() {
		val state = CalculatorState().digit(5).digit(0).percent().toggleSign()

		assertEquals("-0.5", state.display)
	}

	@Test
	fun `expression displays operator and operands during calculation`() {
		val state = CalculatorState()
			.digit(1)
			.digit(2)
			.operator(CalculatorOperation.ADD)
			.digit(5)

		assertEquals("12 +", state.expression)
		assertEquals("5", state.display)

		val evaluated = state.evaluate()
		assertEquals("12 + 5 =", evaluated.expression)
		assertEquals("17", evaluated.display)
	}

	@Test
	fun `clear resets expression and display`() {
		val state = CalculatorState()
			.digit(9)
			.operator(CalculatorOperation.MULTIPLY)
			.digit(9)
			.evaluate()
			.clear()

		assertEquals("", state.expression)
		assertEquals("0", state.display)
	}
}
