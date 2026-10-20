package io.github.melastore.shelf.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * PBKDF2-HMAC-SHA256 on the platform's native HMAC.
 *
 * Android's SecretKeyFactory runs it on BouncyCastle in Java, with a JNI hop per digest call, and
 * measured twice as slow on the emulator. Same standard, same UTF-8 password bytes, same output, so
 * every gate, trailer and manifest written before still opens. Anything the fast path cannot take
 * (an empty password, characters that are not valid UTF-16) goes to the platform's as before.
 */
internal object Pbkdf2 {

	fun sha256(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray = if (nativeHmac) {
		runCatching { native(password, salt, iterations, keyBits / 8) }
			.getOrElse { platform(password, salt, iterations, keyBits) }
	} else {
		platform(password, salt, iterations, keyBits)
	}

	// Only a win on the device's own provider. Under Robolectric the HMAC is a JNI build that pays
	// per call, and a call per round made the suite three times slower and its timeouts trip.
	private val nativeHmac: Boolean by lazy {
		runCatching { Mac.getInstance("HmacSHA256").provider.name == "AndroidOpenSSL" }.getOrDefault(false)
	}

	internal fun native(password: CharArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
		val secret = utf8(password)
		val u = ByteArray(BLOCK)
		val t = ByteArray(BLOCK)
		try {
			val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
			val out = ByteArray(length)
			var block = 1
			var offset = 0
			while (offset < length) {
				mac.update(salt)
				mac.update(ByteBuffer.allocate(4).putInt(block).array())
				mac.doFinal(u, 0)
				u.copyInto(t)
				repeat(iterations - 1) {
					mac.update(u)
					mac.doFinal(u, 0)
					for (i in 0 until BLOCK) t[i] = t[i] xor u[i]
				}
				val take = minOf(BLOCK, length - offset)
				t.copyInto(out, offset, 0, take)
				offset += take
				block++
			}
			return out
		} finally {
			secret.fill(0)
			u.fill(0)
			t.fill(0)
		}
	}

	private fun utf8(password: CharArray): ByteArray {
		val encoded = Charsets.UTF_8.newEncoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.encode(CharBuffer.wrap(password))
		return ByteArray(encoded.remaining()).also(encoded::get).also {
			if (encoded.hasArray()) encoded.array().fill(0)
		}
	}

	private fun platform(password: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
		val spec = PBEKeySpec(password, salt, iterations, keyBits)
		return try {
			SecretKeyFactory.getInstance("PBKDF2withHmacSHA256").generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private const val BLOCK = 32
}
