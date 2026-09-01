package io.github.melastore.shelf.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class CalculatorState(
	val display: String = "0",
	val accumulator: BigDecimal? = null,
	val operation: CalculatorOperation? = null,
	val replaceDisplay: Boolean = false,
) {
	fun digit(value: Int): CalculatorState {
		require(value in 0..9)
		val next = when {
			replaceDisplay || display == "0" || display == "Error" -> value.toString()
			display.length >= MAX_DISPLAY -> display
			else -> display + value
		}
		return copy(display = next, replaceDisplay = false)
	}

	fun decimal(): CalculatorState {
		if (display == "Error") return copy(display = "0.", replaceDisplay = false)
		if (replaceDisplay) return copy(display = "0.", replaceDisplay = false)
		return if ('.' in display) this else copy(display = "$display.")
	}

	fun clear(): CalculatorState = CalculatorState()

	fun toggleSign(): CalculatorState = displayNumber()?.let {
		copy(display = format(it.negate()))
	} ?: this

	fun percent(): CalculatorState = displayNumber()?.let {
		copy(display = format(it.divide(HUNDRED)), replaceDisplay = true)
	} ?: this

	fun operator(next: CalculatorOperation): CalculatorState {
		val current = displayNumber() ?: return CalculatorState(display = "Error", replaceDisplay = true)
		val resolved = if (operation != null && !replaceDisplay) calculate(accumulator, current, operation) else current
		return if (resolved == null) {
			CalculatorState(display = "Error", replaceDisplay = true)
		} else {
			copy(
				display = format(resolved),
				accumulator = resolved,
				operation = next,
				replaceDisplay = true,
			)
		}
	}

	/** Applies the pending operation. Named for the key, not for [Any.equals]. */
	fun evaluate(): CalculatorState {
		val current = displayNumber() ?: return CalculatorState(display = "Error", replaceDisplay = true)
		val result = calculate(accumulator, current, operation) ?: return CalculatorState(
			display = "Error",
			replaceDisplay = true,
		)
		return CalculatorState(display = format(result), replaceDisplay = true)
	}

	private fun displayNumber(): BigDecimal? = display.toBigDecimalOrNull()

	private fun calculate(left: BigDecimal?, right: BigDecimal, operation: CalculatorOperation?,): BigDecimal? {
		if (left == null || operation == null) return right
		return when (operation) {
			CalculatorOperation.ADD -> left + right

			CalculatorOperation.SUBTRACT -> left - right

			CalculatorOperation.MULTIPLY -> left * right

			CalculatorOperation.DIVIDE -> if (right.compareTo(BigDecimal.ZERO) == 0) {
				null
			} else {
				left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
			}
		}
	}

	private fun format(value: BigDecimal): String {
		val plain = value.stripTrailingZeros().toPlainString()
		if (plain.length <= MAX_DISPLAY) return plain
		return value.round(DISPLAY_PRECISION).stripTrailingZeros().toEngineeringString()
	}

	private companion object {
		const val MAX_DISPLAY = 14
		const val DIVISION_SCALE = 10
		val HUNDRED = BigDecimal(100)
		val DISPLAY_PRECISION = MathContext(10, RoundingMode.HALF_UP)
	}
}

enum class CalculatorOperation { ADD, SUBTRACT, MULTIPLY, DIVIDE }
