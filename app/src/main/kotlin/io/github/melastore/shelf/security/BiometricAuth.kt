package io.github.melastore.shelf.security

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Biometric authentication plus a Keystore-wrapped copy of the primary content credential. */
object BiometricAuth {

	private const val AUTHENTICATOR = BiometricManager.Authenticators.BIOMETRIC_STRONG
	private const val KEY_AUTHENTICATOR = KeyProperties.AUTH_BIOMETRIC_STRONG
	private const val KEY_NAME = "shelf.biometric.credential.v2"
	private const val LEGACY_KEY_NAME = "shelf.biometric.gate"
	private const val KEY_STORE = "AndroidKeyStore"
	private const val PREFERENCES = "biometric_credential"
	private const val CIPHERTEXT = "ciphertext"
	private const val IV = "iv"
	private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/" +
		KeyProperties.ENCRYPTION_PADDING_NONE

	/** Why an attempt ended. A missing wrapper drops older installs back to the credential prompt. */
	enum class Outcome { SUCCEEDED, FALLBACK, ENROLMENT_CHANGED, CREDENTIAL_MISSING }

	fun isAvailable(context: Context): Boolean {
		val manager = context.getSystemService(BiometricManager::class.java) ?: return false
		return manager.canAuthenticate(AUTHENTICATOR) == BiometricManager.BIOMETRIC_SUCCESS
	}

	/** True only if both the encrypted credential and its Keystore key are present. */
	fun hasCredential(context: Context): Boolean = readBlob(context) != null && runCatching {
		keyStore().containsAlias(KEY_NAME)
	}.getOrDefault(false)

	/**
	 * Wraps [credential] after a strong-biometric match. The copy is app-private and its key is
	 * non-exportable, needs a biometric match per use, and dies when the enrolment changes.
	 */
	fun enroll(
		activity: Activity,
		credential: CharArray,
		title: String,
		subtitle: String,
		negativeButton: String,
		onResult: (Outcome) -> Unit,
	): CancellationSignal? {
		val secret = credential.copyOf()
		credential.fill(' ')
		if (!CredentialRules.isStorable(secret)) {
			secret.fill(' ')
			onResult(Outcome.FALLBACK)
			return null
		}

		// Enabling is a fresh enrolment: drop any stale or legacy key before binding to today's
		// biometrics.
		reset(activity)
		val cipher = prepareEncrypt() ?: run {
			secret.fill(' ')
			onResult(Outcome.FALLBACK)
			return null
		}
		return prompt(
			activity = activity,
			cipher = cipher,
			title = title,
			subtitle = subtitle,
			negativeButton = negativeButton,
			onCancelled = { secret.fill(' ') },
			onFallback = {
				secret.fill(' ')
				onResult(Outcome.FALLBACK)
			},
		) { authenticated ->
			val plain = CredentialBytes.encode(secret)
			try {
				val encrypted = authenticated.doFinal(plain)
				val saved = preferences(activity).edit()
					.putString(CIPHERTEXT, Base64.getEncoder().encodeToString(encrypted))
					.putString(IV, Base64.getEncoder().encodeToString(authenticated.iv))
					.commit()
				encrypted.fill(0)
				onResult(if (saved) Outcome.SUCCEEDED else Outcome.FALLBACK)
			} catch (_: KeyPermanentlyInvalidatedException) {
				reset(activity)
				onResult(Outcome.ENROLMENT_CHANGED)
			} catch (_: Exception) {
				reset(activity)
				onResult(Outcome.FALLBACK)
			} finally {
				plain.fill(0)
				secret.fill(' ')
			}
		}
	}

	/** Authenticates and hands [onResult] a short-lived credential it then owns. */
	fun authenticate(
		activity: Activity,
		title: String,
		subtitle: String,
		negativeButton: String,
		onResult: (Outcome, CharArray?) -> Unit,
	): CancellationSignal? {
		val blob = readBlob(activity) ?: run {
			onResult(Outcome.CREDENTIAL_MISSING, null)
			return null
		}
		val prepared = prepareDecrypt(blob.iv)
		val cipher = when (prepared) {
			is Prepared.Ready -> prepared.cipher

			Prepared.Invalidated -> {
				reset(activity)
				onResult(Outcome.ENROLMENT_CHANGED, null)
				return null
			}

			Prepared.Failed -> {
				onResult(Outcome.FALLBACK, null)
				return null
			}
		}

		return prompt(
			activity = activity,
			cipher = cipher,
			title = title,
			subtitle = subtitle,
			negativeButton = negativeButton,
			onCancelled = {
				blob.ciphertext.fill(0)
				blob.iv.fill(0)
			},
			onFallback = {
				blob.ciphertext.fill(0)
				blob.iv.fill(0)
				onResult(Outcome.FALLBACK, null)
			},
		) { authenticated ->
			var plain = byteArrayOf()
			try {
				plain = authenticated.doFinal(blob.ciphertext)
				val recovered = CredentialBytes.decode(plain)
				if (recovered == null) {
					reset(activity)
					onResult(Outcome.CREDENTIAL_MISSING, null)
				} else {
					onResult(Outcome.SUCCEEDED, recovered)
				}
			} catch (_: KeyPermanentlyInvalidatedException) {
				reset(activity)
				onResult(Outcome.ENROLMENT_CHANGED, null)
			} catch (_: Exception) {
				reset(activity)
				onResult(Outcome.CREDENTIAL_MISSING, null)
			} finally {
				plain.fill(0)
				blob.ciphertext.fill(0)
				blob.iv.fill(0)
			}
		}
	}

	/** Deletes the wrapper and both the current and legacy keys. */
	@Suppress("ApplySharedPref", "UseKtx") // Synchronous: the key is gone the moment this returns.
	fun reset(context: Context) {
		preferences(context).edit().clear().commit()
		runCatching {
			keyStore().apply {
				deleteEntry(KEY_NAME)
				deleteEntry(LEGACY_KEY_NAME)
			}
		}
	}

	private fun prompt(
		activity: Activity,
		cipher: Cipher,
		title: String,
		subtitle: String,
		negativeButton: String,
		onCancelled: () -> Unit,
		onFallback: () -> Unit,
		onAuthenticated: (Cipher) -> Unit,
	): CancellationSignal {
		val cancellation = CancellationSignal()
		var finished = false

		fun cancel() {
			if (finished) return
			finished = true
			onCancelled()
		}

		fun fallback() {
			if (finished) return
			finished = true
			onFallback()
		}

		val prompt = BiometricPrompt.Builder(activity)
			.setTitle(title)
			.setSubtitle(subtitle)
			.setAllowedAuthenticators(AUTHENTICATOR)
			.setNegativeButton(negativeButton, activity.mainExecutor) { _, _ -> fallback() }
			.build()

		prompt.authenticate(
			BiometricPrompt.CryptoObject(cipher),
			cancellation,
			activity.mainExecutor,
			object : BiometricPrompt.AuthenticationCallback() {
				override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
					if (finished) return
					finished = true
					val authenticated = result.cryptoObject?.cipher
					if (authenticated == null) onFallback() else onAuthenticated(authenticated)
				}

				override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
					when (errorCode) {
						BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
						BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
						-> cancel()

						else -> fallback()
					}
				}
			},
		)
		cancellation.setOnCancelListener(::cancel)
		return cancellation
	}

	private fun prepareEncrypt(): Cipher? = runCatching {
		Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, newKey()) }
	}.getOrNull()

	private fun prepareDecrypt(iv: ByteArray): Prepared {
		val key = existingKey() ?: return Prepared.Invalidated
		return try {
			Prepared.Ready(
				Cipher.getInstance(TRANSFORMATION).apply {
					init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
				},
			)
		} catch (_: KeyPermanentlyInvalidatedException) {
			Prepared.Invalidated
		} catch (_: Exception) {
			Prepared.Failed
		}
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
				.setUserAuthenticationRequired(true)
				.setUserAuthenticationParameters(0, KEY_AUTHENTICATOR)
				.setInvalidatedByBiometricEnrollment(true)
				.build(),
		)
		return generator.generateKey()
	}

	private fun existingKey(): SecretKey? = runCatching {
		(keyStore().getEntry(KEY_NAME, null) as? KeyStore.SecretKeyEntry)?.secretKey
	}.getOrNull()

	private fun readBlob(context: Context): CredentialBlob? = runCatching {
		val values = preferences(context)
		val ciphertextBase64 = values.getString(CIPHERTEXT, null) ?: return null
		val ivBase64 = values.getString(IV, null) ?: return null
		val ciphertext = Base64.getDecoder().decode(ciphertextBase64)
		val iv = Base64.getDecoder().decode(ivBase64)
		if (ciphertext.size !in 1..MAX_CIPHERTEXT_BYTES || iv.size != GCM_IV_BYTES) return null
		CredentialBlob(ciphertext, iv)
	}.getOrNull()

	private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

	private fun keyStore(): KeyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }

	private data class CredentialBlob(val ciphertext: ByteArray, val iv: ByteArray)

	private sealed interface Prepared {
		data class Ready(val cipher: Cipher) : Prepared
		data object Invalidated : Prepared
		data object Failed : Prepared
	}

	private const val GCM_TAG_BITS = 128
	private const val GCM_IV_BYTES = 12

	/** [CredentialRules.MAX_LENGTH] chars at four UTF-8 bytes each, plus the GCM tag. */
	private const val MAX_CIPHERTEXT_BYTES = CredentialRules.MAX_LENGTH * 4 + GCM_TAG_BITS / 8
}
