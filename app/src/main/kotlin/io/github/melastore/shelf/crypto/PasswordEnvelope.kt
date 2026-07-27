package io.github.melastore.shelf.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned password-encrypted envelope used for small recovery bundles, never folder contents. */
object PasswordEnvelope {

	fun encrypt(plaintext: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
		require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "recovery data is too large" }
		require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "invalid KDF work factor" }
		val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
		val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
		val encryptedLength = plaintext.size + GCM_TAG_BYTES
		val header = header(iterations, salt, nonce, encryptedLength)
		val key = derive(password, salt, iterations)
		return try {
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
			cipher.updateAAD(header)
			header + cipher.doFinal(plaintext)
		} finally {
			key.fill(0)
		}
	}

	fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
		require(envelope.size <= MAX_ENVELOPE_BYTES) { "recovery file is too large" }
		val input = DataInputStream(ByteArrayInputStream(envelope))
		val magic = ByteArray(MAGIC.size).also(input::readFully)
		require(magic.contentEquals(MAGIC)) { "not a Shelf recovery file" }
		val version = input.readUnsignedByte()
		require(version == VERSION) { "unsupported recovery format" }
		val iterations = input.readInt()
		require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "invalid KDF work factor" }
		val salt = ByteArray(SALT_BYTES).also(input::readFully)
		val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
		val encryptedLength = input.readInt()
		require(encryptedLength in GCM_TAG_BYTES..MAX_ENCRYPTED_BYTES) { "invalid encrypted length" }
		require(input.available() == encryptedLength) { "truncated or trailing recovery data" }
		val headerLength = envelope.size - encryptedLength
		val header = envelope.copyOfRange(0, headerLength)
		val encrypted = ByteArray(encryptedLength).also(input::readFully)
		val key = derive(password, salt, iterations)
		return try {
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
			cipher.updateAAD(header)
			cipher.doFinal(encrypted)
		} finally {
			key.fill(0)
		}
	}

	private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray, encryptedLength: Int): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.write(MAGIC)
				output.writeByte(VERSION)
				output.writeInt(iterations)
				output.write(salt)
				output.write(nonce)
				output.writeInt(encryptedLength)
			}
			bytes.toByteArray()
		}

	private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
		val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
		return try {
			SecretKeyFactory.getInstance("PBKDF2withHmacSHA256").generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	private val MAGIC = "SHELFREC".toByteArray(Charsets.US_ASCII)
	private const val VERSION = 1
	private const val SALT_BYTES = 16
	private const val NONCE_BYTES = 12
	private const val GCM_TAG_BYTES = 16
	private const val KEY_BITS = 256
	private const val DEFAULT_ITERATIONS = 600_000
	internal const val MIN_ITERATIONS = 100_000
	private const val MAX_ITERATIONS = 2_000_000
	private const val MAX_PLAINTEXT_BYTES = 8 * 1024 * 1024
	private const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
	const val MAX_ENVELOPE_BYTES = MAX_ENCRYPTED_BYTES + 64
}
