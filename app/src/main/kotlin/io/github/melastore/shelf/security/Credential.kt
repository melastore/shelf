package io.github.melastore.shelf.security

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * How the owner proves who they are.
 *
 * All three end at the same place: a [CharArray] handed to [PassphraseGate] for comparison and to the
 * folder machinery as the passphrase its file headers are encrypted under. Nothing below the UI knows
 * which one was used, so the choice can be changed without touching a single hidden folder — as long
 * as the credential itself does not change with it.
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
 * The 3×3 lock pattern, as the digits of the dots it joins.
 *
 * A pattern is nine dots and an order, which is a short string of digits and nothing more. Encoding it
 * that way rather than inventing a format means a pattern is the same kind of secret as a PIN all the
 * way down: the same gate, the same key derivation, and the same Keystore wrappers, whose length and
 * character limits it already satisfies.
 */
object PatternCode {

	const val SIDE = 3
	const val DOTS = SIDE * SIDE
	const val MIN_DOTS = 4

	fun isValid(dots: List<Int>): Boolean = dots.size in MIN_DOTS..DOTS &&
		dots.all { it in 0 until DOTS } && dots.toSet().size == dots.size

	fun encode(dots: List<Int>): CharArray = CharArray(dots.size) { '0' + dots[it] }

	/** Whether [encoded] could have come from [encode], which is the only shape a gate ever sees. */
	fun isEncoded(encoded: CharArray): Boolean {
		if (encoded.any { it < '0' || it > '0' + DOTS - 1 }) return false
		return isValid(encoded.map { it - '0' })
	}

	/**
	 * The dots between [from] and [to] that a straight line passes through. Android's pattern lock
	 * picks up every dot on the way, and a pattern the user drew but Shelf did not record the same way
	 * would simply never match.
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
 * A knock code, as the digits of the quarters it taps.
 *
 * Four unmarked quarters and an order. Nothing on screen says where the divisions are or how long
 * the code is, so it can be entered without looking at the phone at all, and someone watching sees
 * taps landing on a blank square rather than a keypad. Repeats are allowed, which is what separates
 * it from a pattern and why four taps still cover 256 codes rather than a handful of paths.
 *
 * Encoded as digits for the same reason a pattern is: the gate, the key derivation and the Keystore
 * wrappers then treat it as the same kind of secret as a PIN.
 */
object KnockCode {

	const val SIDE = 2
	const val QUARTERS = SIDE * SIDE
	const val MIN_TAPS = 4
	const val MAX_TAPS = 12

	fun isValid(taps: List<Int>): Boolean = taps.size in MIN_TAPS..MAX_TAPS && taps.all { it in 0 until QUARTERS }

	fun encode(taps: List<Int>): CharArray = CharArray(taps.size) { '0' + taps[it] }

	/** Whether [encoded] could have come from [encode], which is the only shape a gate ever sees. */
	fun isEncoded(encoded: CharArray): Boolean {
		if (encoded.any { it < '0' || it > '0' + QUARTERS - 1 }) return false
		return isValid(encoded.map { it - '0' })
	}
}

/**
 * The rules every credential is held to, wherever it is checked.
 *
 * They live in one place because two of the checks are not in the UI at all: the biometric wrapper and
 * the emergency re-hide wrapper both re-validate what comes back out of the Keystore before they will
 * hand it to the folder machinery. A password that the setup screen accepts but those two reject is a
 * vault whose owner quietly loses biometric unlock and automatic re-hiding without being told.
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

		// A pattern is nine dots joined once each, so a valid one is short, distinct and inside the
		// grid. The prompt already guarantees that; this is what stops anything else reaching the gate.
		CredentialKind.PATTERN -> when {
			credential.size < PatternCode.MIN_DOTS -> CredentialFault.PATTERN_TOO_SHORT
			credential.size > PatternCode.DOTS -> CredentialFault.TOO_LONG
			!PatternCode.isEncoded(credential) -> CredentialFault.PATTERN_TOO_SHORT
			else -> null
		}

		// Four quarters tapped in order, repeats allowed. The pad already guarantees the shape; this is
		// what stops anything else reaching the gate.
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

	/** Whether a credential can be put in, and taken back out of, a Keystore-wrapped copy intact. */
	fun isStorable(credential: CharArray): Boolean = credential.size in MIN_PIN..MAX_LENGTH &&
		credential.none { it.isISOControl() }
}

/**
 * Moves a credential between characters and bytes without ever building a String from it.
 *
 * A String cannot be wiped, and one holding the passphrase would sit in the heap until a garbage
 * collector happened to move it. The encoding is UTF-8 rather than a byte per character: passwords are
 * typed on the user's own keyboard, and narrowing each character to its low byte would silently mangle
 * every one that is not Latin-1 — a credential that then never matches the folders it locked.
 */
object CredentialBytes {

	fun encode(credential: CharArray): ByteArray {
		val buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(credential))
		val bytes = ByteArray(buffer.remaining())
		buffer.get(bytes)
		wipe(buffer)
		return bytes
	}

	/** Null when [bytes] are not the UTF-8 of a storable credential. */
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

	/** The decoder allocates its own buffer, and it holds the credential until something overwrites it. */
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
