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
 * A device-bound re-hide capability, kept only while a tracked folder is exposed.
 *
 * The notification outlives Shelf's process, so an in-memory credential cannot finish name and
 * header protection once Android has killed the app. Separate from biometric unlock on purpose: it
 * recreates an already-exposed session just long enough to put folders back, and the ciphertext is
 * deleted the moment the last tracked folder is hidden.
 */
object EmergencyCredentialStore {

	@Synchronized
	fun arm(context: Context, credential: CharArray): Boolean {
		if (!CredentialRules.isStorable(credential)) return false
		val plain = CredentialBytes.encode(credential)
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

	/** A caller-owned credential, or null if the key was invalidated or the stored copy is damaged. */
	@Synchronized
	fun load(context: Context): CharArray? {
		val values = preferences(context)
		var encrypted = byteArrayOf()
		var iv = byteArrayOf()
		var plain = byteArrayOf()
		return try {
			val encryptedBase64 = values.getString(CIPHERTEXT, null) ?: return null
			val ivBase64 = values.getString(IV, null) ?: return null
			encrypted = Base64.getDecoder().decode(encryptedBase64)
			iv = Base64.getDecoder().decode(ivBase64)
			if (encrypted.size !in 1..MAX_CIPHERTEXT_BYTES || iv.size != GCM_IV_BYTES) return null
			val key = existingKey() ?: return null
			plain = Cipher.getInstance(TRANSFORMATION).run {
				init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
				doFinal(encrypted)
			}
			CredentialBytes.decode(plain)
		} catch (_: Exception) {
			null
		} finally {
			encrypted.fill(0)
			iv.fill(0)
			plain.fill(0)
		}
	}

	/** Drops the ciphertext. The non-exportable key is harmless without it. */
	@Synchronized
	@Suppress("ApplySharedPref", "UseKtx") // Synchronous: an apply() could still be queued at a kill.
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
			// A changed device lock invalidates the key. Nothing useful is stored yet, so drop the dead
			// alias and retry once.
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
	private const val GCM_TAG_BITS = 128
	private const val GCM_IV_BYTES = 12

	/** [CredentialRules.MAX_LENGTH] chars at four UTF-8 bytes each, plus the GCM tag. */
	private const val MAX_CIPHERTEXT_BYTES = CredentialRules.MAX_LENGTH * 4 + GCM_TAG_BITS / 8
}
