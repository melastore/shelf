package io.github.melastore.shelf.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A device-bound, temporary re-hide capability kept only while a tracked folder is exposed.
 *
 * The notification can outlive Shelf's process, so an in-memory PIN cannot finish filename and
 * header protection after Android has killed the app. This wrapper is deliberately separate from
 * biometric unlock: it can only recreate the already-exposed session long enough to put folders
 * back out of sight, and its ciphertext is deleted as soon as every tracked folder is hidden.
 */
object EmergencyCredentialStore {

	@Synchronized
	fun arm(context: Context, credential: CharArray): Boolean {
		if (credential.size !in MIN_PIN_LENGTH..MAX_PIN_LENGTH || credential.any { !it.isDigit() }) {
			return false
		}
		val plain = ByteArray(credential.size) { credential[it].code.toByte() }
		return try {
			val cipher = prepareEncrypt() ?: return false
			val encrypted = cipher.doFinal(plain)
			val saved = preferences(context).edit()
				.putString(CIPHERTEXT, Base64.getEncoder().encodeToString(encrypted))
				.putString(IV, Base64.getEncoder().encodeToString(cipher.iv))
				.commit()
			encrypted.fill(0)
			saved
		} catch (_: Exception) {
			false
		} finally {
			plain.fill(0)
		}
	}

	/** Returns a caller-owned credential, or null after key invalidation or damaged storage. */
	@Synchronized
	fun load(context: Context): CharArray? {
		val values = preferences(context)
		var encrypted = byteArrayOf()
		var iv = byteArrayOf()
		var plain = byteArrayOf()
		return try {
			encrypted = Base64.getDecoder().decode(values.getString(CIPHERTEXT, null))
			iv = Base64.getDecoder().decode(values.getString(IV, null))
			if (encrypted.size !in 1..MAX_CIPHERTEXT_BYTES || iv.size != GCM_IV_BYTES) return null
			val key = existingKey() ?: return null
			plain = Cipher.getInstance(TRANSFORMATION).run {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
				doFinal(encrypted)
			}
			if (
				plain.size !in MIN_PIN_LENGTH..MAX_PIN_LENGTH ||
				plain.any { it.toInt() !in '0'.code..'9'.code }
			) {
				return null
			}
			CharArray(plain.size) { plain[it].toInt().toChar() }
		} catch (_: Exception) {
			null
		} finally {
			encrypted.fill(0)
			iv.fill(0)
			plain.fill(0)
		}
	}

	/** Removes the ciphertext immediately; the non-exportable key is harmless without it. */
	@Synchronized
	fun clear(context: Context) {
		preferences(context).edit().clear().commit()
	}

	private fun newKey(): SecretKey {
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE)
		generator.init(
			KeyGenParameterSpec.Builder(
				KEY_NAME,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setRandomizedEncryptionRequired(true)
				.build(),
		)
		return generator.generateKey()
	}

	private fun prepareEncrypt(): Cipher? {
		fun prepare(key: SecretKey) = Cipher.getInstance(TRANSFORMATION).apply {
			init(Cipher.ENCRYPT_MODE, key)
		}
		return runCatching { prepare(existingKey() ?: newKey()) }.getOrElse {
			// A changed device lock can invalidate a Keystore key. There is no useful ciphertext yet,
			// so discard the dead alias and make one clean retry.
			runCatching { keyStore().deleteEntry(KEY_NAME) }
			runCatching { prepare(newKey()) }.getOrNull()
		}
	}

	private fun existingKey(): SecretKey? = runCatching {
		(keyStore().getEntry(KEY_NAME, null) as? KeyStore.SecretKeyEntry)?.secretKey
	}.getOrNull()

	private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

	private fun keyStore(): KeyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }

	private const val KEY_NAME = "shelf.emergency.rehide.v1"
	private const val KEY_STORE = "AndroidKeyStore"
	private const val PREFERENCES = "emergency_rehide"
	private const val CIPHERTEXT = "ciphertext"
	private const val IV = "iv"
	private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/" +
		"${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
	private const val MIN_PIN_LENGTH = 4
	private const val MAX_PIN_LENGTH = 12
	private const val GCM_TAG_BITS = 128
	private const val GCM_IV_BYTES = 12
	private const val MAX_CIPHERTEXT_BYTES = MAX_PIN_LENGTH + GCM_TAG_BITS / 8
}
