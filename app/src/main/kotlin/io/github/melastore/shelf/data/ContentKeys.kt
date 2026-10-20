package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.crypto.PasswordEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * One salt per install for each kind of key, so the key [io.github.melastore.shelf.crypto.KeyCache]
 * holds for the session covers every folder hidden here, not just the one it was derived for.
 *
 * Every trailer and manifest still carries its own salt, so a folder stays recoverable from the
 * credential alone after app data is cleared, and folders from before this keep theirs. Not secret:
 * it sits next to every file it was used on.
 */
object ContentKeys {
	fun headerSalt(context: Context): ByteArray = AppPreferences(context).installSalt("header", HeaderCipher.SALT_LENGTH)

	fun manifestSalt(context: Context): ByteArray =
		AppPreferences(context).installSalt("manifest", PasswordEnvelope.SALT_BYTES)

	/**
	 * Derives both keys while the owner is still looking at the folder list, side by side, so the
	 * first hide or restore after unlocking finds them ready. One asked for before this finishes
	 * waits for the same derivation rather than starting its own.
	 */
	suspend fun prepare(context: Context) = coroutineScope {
		launch(Dispatchers.Default) {
			withCredential { HeaderCipher.deriveKey(it, headerSalt(context)) }
		}
		launch(Dispatchers.Default) {
			withCredential { PasswordEnvelope.prepareKey(it, manifestSalt(context), FolderNameProtector.MANIFEST_ROUNDS) }
		}
	}

	private inline fun withCredential(block: (CharArray) -> Unit) {
		val credential = ContentCredential.copy() ?: return
		try {
			block(credential)
		} finally {
			credential.fill(' ')
		}
	}
}
