package io.github.melastore.shelf.data

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class CalculatorState(
	val display: String = "0",
	val accumulator: BigDecimal? = null,
	val operation: CalculatorOperation? = null,
	val replaceDisplay: Boolean = false,
	val expression: String = "",
) {
	fun digit(value: Int): CalculatorState {
		require(value in 0..9)
		val nextExpr = if (replaceDisplay && operation == null) "" else expression
		val next = when {
			replaceDisplay || display == "0" || display == "Error" -> value.toString()
			display.length >= MAX_DISPLAY -> display
			else -> display + value
		}
		return copy(display = next, replaceDisplay = false, expression = nextExpr)
	}

	fun decimal(): CalculatorState {
		val nextExpr = if (replaceDisplay && operation == null) "" else expression
		if (display == "Error") return copy(display = "0.", replaceDisplay = false, expression = nextExpr)
		if (replaceDisplay) return copy(display = "0.", replaceDisplay = false, expression = nextExpr)
		return if ('.' in display) this else copy(display = "$display.", expression = nextExpr)
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
				expression = "${format(resolved)} ${next.symbol}",
			)
		}
	}

	/** Applies the pending operation. Named for the key, not for [Any.equals]. */
	fun evaluate(): CalculatorState {
		val current = displayNumber() ?: return CalculatorState(display = "Error", replaceDisplay = true)
		if (operation != null && accumulator != null) {
			val result = calculate(accumulator, current, operation) ?: return CalculatorState(
				display = "Error",
				replaceDisplay = true,
			)
			return CalculatorState(
				display = format(result),
				replaceDisplay = true,
				expression = "${format(accumulator)} ${operation.symbol} ${format(current)} =",
			)
		}
		return CalculatorState(
			display = format(current),
			replaceDisplay = true,
			expression = "${format(current)} =",
		)
	}

	private fun displayNumber(): BigDecimal? = display.toBigDecimalOrNull()

	/** Null for anything a phone calculator would show as Error, overflow included. */
	private fun calculate(left: BigDecimal?, right: BigDecimal, operation: CalculatorOperation?): BigDecimal? {
		if (left == null || operation == null) return right
		val result = runCatching { apply(left, right, operation) }.getOrNull() ?: return null
		return result.takeIf { it.signum() == 0 || exponentOf(it) in -MAX_EXPONENT..MAX_EXPONENT }
	}

	private fun apply(left: BigDecimal, right: BigDecimal, operation: CalculatorOperation): BigDecimal? =
		when (operation) {
			CalculatorOperation.ADD -> left + right

			CalculatorOperation.SUBTRACT -> left - right

			CalculatorOperation.MULTIPLY -> left.multiply(right, ARITHMETIC)

			CalculatorOperation.DIVIDE -> if (right.compareTo(BigDecimal.ZERO) == 0) {
				null
			} else {
				left.divide(right, DIVISION_SCALE, RoundingMode.HALF_UP)
			}
		}

	private fun format(value: BigDecimal): String {
		// The plain form is as long as the exponent is large, so it is only built when it could fit.
		// Squaring a big result a few dozen times otherwise asked for a string of a gigabyte.
		val stripped = value.stripTrailingZeros()
		if (stripped.signum() == 0 || exponentOf(stripped) in -MAX_DISPLAY..MAX_DISPLAY) {
			val plain = stripped.toPlainString()
			if (plain.length <= MAX_DISPLAY) return plain
		}
		return value.round(DISPLAY_PRECISION).stripTrailingZeros().toEngineeringString()
	}

	private fun exponentOf(value: BigDecimal): Int = value.precision() - value.scale() - 1

	private companion object {
		const val MAX_DISPLAY = 14
		const val DIVISION_SCALE = 10
		const val MAX_EXPONENT = 999
		val ARITHMETIC = MathContext(34, RoundingMode.HALF_UP)
		val HUNDRED = BigDecimal(100)
		val DISPLAY_PRECISION = MathContext(10, RoundingMode.HALF_UP)
	}
}

enum class CalculatorOperation(val symbol: String) {
	ADD("+"),
	SUBTRACT("−"),
	MULTIPLY("×"),
	DIVIDE("÷"),
}
