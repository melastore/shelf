package io.github.melastore.shelf.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * How the owner proves who they are.
 *
 * All four end at the same place: a [CharArray] handed to [PassphraseGate] to compare, and to the
 * folder machinery as the passphrase file headers are encrypted under. Nothing below the UI knows
 * which was used, so the kind can change without touching a hidden folder, as long as the credential
 * itself stays the same.
 */
enum class CredentialKind { PIN, PASSWORD, PATTERN, KNOCK }

/** Why a credential was rejected before anything was stored. */
enum class CredentialFault {
	TOO_SHORT,
	TOO_LONG,
	PIN_NOT_DIGITS,
	PATTERN_TOO_SHORT,
	KNOCK_TOO_SHORT,
	PASSWORD_UNSUPPORTED,
}

/**
 * The 3x3 lock pattern, encoded as the digits of the dots it joins.
 *
 * Nine dots and an order is a short digit string, so a pattern goes through the same gate, key
 * derivation and Keystore wrappers as a PIN instead of needing a format of its own.
 */
object PatternCode {

	const val SIDE = 3
	const val DOTS = SIDE * SIDE
	const val MIN_DOTS = 4

	fun isValid(dots: List<Int>): Boolean = dots.size in MIN_DOTS..DOTS &&
		dots.all { it in 0 until DOTS } && dots.toSet().size == dots.size

	fun encode(dots: List<Int>): CharArray = CharArray(dots.size) { '0' + dots[it] }

	/** Whether [encoded] could have come from [encode]. The only shape a gate ever sees. */
	fun isEncoded(encoded: CharArray): Boolean {
		if (encoded.any { it < '0' || it > '0' + DOTS - 1 }) return false
		return isValid(encoded.map { it - '0' })
	}

	/**
	 * Dots a straight line from [from] to [to] passes through. Android's pattern lock picks these up
	 * on the way, so we have to as well or the pattern the user drew never matches the one recorded.
	 */
	fun crossed(from: Int, to: Int): List<Int> {
		val rowStep = to / SIDE - from / SIDE
		val columnStep = to % SIDE - from % SIDE
		val steps = maxOf(kotlin.math.abs(rowStep), kotlin.math.abs(columnStep))
		if (steps < 2 || rowStep % steps != 0 || columnStep % steps != 0) return emptyList()
		return (1 until steps).map { step ->
			(from / SIDE + rowStep / steps * step) * SIDE + (from % SIDE + columnStep / steps * step)
		}
	}
}

/**
 * A knock code, encoded as the digits of the quarters it taps.
 *
 * Four unmarked quarters and an order. Nothing on screen shows where the divisions are or how long
 * the code is, so it can be entered without looking and a watcher sees taps on a blank square.
 * Repeats are allowed, unlike a pattern, so four taps cover 256 codes.
 *
 * Digits for the same reason [PatternCode] uses them.
 */
object KnockCode {

	const val SIDE = 2
	const val QUARTERS = SIDE * SIDE
	const val MIN_TAPS = 4
	const val MAX_TAPS = 12

	fun isValid(taps: List<Int>): Boolean = taps.size in MIN_TAPS..MAX_TAPS && taps.all { it in 0 until QUARTERS }

	fun encode(taps: List<Int>): CharArray = CharArray(taps.size) { '0' + taps[it] }

	/** Whether [encoded] could have come from [encode]. The only shape a gate ever sees. */
	fun isEncoded(encoded: CharArray): Boolean {
		if (encoded.any { it < '0' || it > '0' + QUARTERS - 1 }) return false
		return isValid(encoded.map { it - '0' })
	}
}

/**
 * The rules every credential is held to, wherever it is checked.
 *
 * One place, because two of the callers are not the UI: the biometric wrapper and the emergency
 * re-hide wrapper both re-validate what comes back out of the Keystore. A password the setup screen
 * accepts but those reject costs the owner biometric unlock and auto re-hiding with no warning.
 */
object CredentialRules {

	const val MIN_PIN = 4
	const val MAX_PIN = 12
	const val MIN_PASSWORD = 6
	const val MAX_LENGTH = 128

	fun validate(kind: CredentialKind, credential: CharArray): CredentialFault? = when (kind) {
		CredentialKind.PIN -> when {
			credential.size < MIN_PIN -> CredentialFault.TOO_SHORT
			credential.size > MAX_PIN -> CredentialFault.TOO_LONG
			credential.any { !it.isDigit() } -> CredentialFault.PIN_NOT_DIGITS
			else -> null
		}

		// Nine dots joined once each: short, distinct, inside the grid. The prompt guarantees that
		// already; this stops anything else reaching the gate.
		CredentialKind.PATTERN -> when {
			credential.size < PatternCode.MIN_DOTS -> CredentialFault.PATTERN_TOO_SHORT
			credential.size > PatternCode.DOTS -> CredentialFault.TOO_LONG
			!PatternCode.isEncoded(credential) -> CredentialFault.PATTERN_TOO_SHORT
			else -> null
		}

		// Four quarters in order, repeats allowed. Same reason as the pattern check above.
		CredentialKind.KNOCK -> when {
			credential.size < KnockCode.MIN_TAPS -> CredentialFault.KNOCK_TOO_SHORT
			credential.size > KnockCode.MAX_TAPS -> CredentialFault.TOO_LONG
			!KnockCode.isEncoded(credential) -> CredentialFault.KNOCK_TOO_SHORT
			else -> null
		}

		CredentialKind.PASSWORD -> when {
			credential.size < MIN_PASSWORD -> CredentialFault.TOO_SHORT
			credential.size > MAX_LENGTH -> CredentialFault.TOO_LONG
			credential.any { it.isISOControl() } -> CredentialFault.PASSWORD_UNSUPPORTED
			else -> null
		}
	}

	/** Whether a credential survives a round trip through a Keystore-wrapped copy. */
	fun isStorable(credential: CharArray): Boolean = credential.size in MIN_PIN..MAX_LENGTH &&
		credential.none { it.isISOControl() }
}

/**
 * Moves a credential between chars and bytes without building a String.
 *
 * A String cannot be wiped and would sit in the heap until the collector got round to it. UTF-8
 * rather than a byte per char: passwords come off the user's own keyboard, and truncating to the low
 * byte would mangle anything outside Latin-1 into a credential that no longer opens its own folders.
 */
object CredentialBytes {

	fun encode(credential: CharArray): ByteArray {
		val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(credential))
		val bytes = ByteArray(buffer.remaining())
		buffer.get(bytes)
		wipe(buffer)
		return bytes
	}

	/** Null unless [bytes] are the UTF-8 of a storable credential. */
	fun decode(bytes: ByteArray): CharArray? {
		val buffer = runCatching { strictDecoder().decode(ByteBuffer.wrap(bytes)) }.getOrNull() ?: return null
		val credential = CharArray(buffer.remaining())
		buffer.get(credential)
		wipe(buffer)
		if (!CredentialRules.isStorable(credential)) {
			credential.fill(' ')
			return null
		}
		return credential
	}

	/** The decoder allocates its own buffer, which holds the credential until it is overwritten. */
	private fun wipe(buffer: CharBuffer) {
		if (buffer.hasArray()) buffer.array().fill(' ')
	}

	private fun wipe(buffer: ByteBuffer) {
		if (buffer.hasArray()) buffer.array().fill(0)
	}

	private fun strictDecoder(): CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
		.onMalformedInput(CodingErrorAction.REPORT)
		.onUnmappableCharacter(CodingErrorAction.REPORT)
}
