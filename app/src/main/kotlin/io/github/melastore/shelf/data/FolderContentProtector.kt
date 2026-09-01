package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.StoragePaths
import java.io.File

/**
 * The primary credential, held only while the real private space is open. It lets a hide protect
 * file headers without asking for a second password, and is wiped with every lock.
 */
object ContentCredential {
	private var value: CharArray? = null
	private val retained = mutableListOf<CharArray>()

	@Synchronized fun set(input: CharArray) {
		value?.fill(' ')
		value = input.copyOf()
	}

	@Synchronized fun copy(): CharArray? = (value ?: retained.lastOrNull())?.copyOf()

	@Synchronized fun isAvailable(): Boolean = value != null || retained.isNotEmpty()

	/**
	 * Keeps a copy alive while an emergency hide closes the UI out from under it.
	 *
	 * Snapshots whatever [copy] would hand out, not just the live session. A second hide starting
	 * while the first still holds a lease would otherwise take an empty one and lose the credential
	 * the moment that first lease closed, halfway through its own folder.
	 */
	@Synchronized fun retain(): AutoCloseable {
		val snapshot = (value ?: retained.lastOrNull())?.copyOf() ?: return AutoCloseable { }
		retained += snapshot
		return AutoCloseable {
			synchronized(this) {
				retained.remove(snapshot)
				snapshot.fill(' ')
			}
		}
	}

	@Synchronized fun clear() {
		value?.fill(' ')
		value = null
	}
}

sealed interface ContentProtectionResult {
	data class Done(val changed: Int) : ContentProtectionResult
	data object NoFiles : ContentProtectionResult
	data object AccessUnavailable : ContentProtectionResult
	data object CredentialRequired : ContentProtectionResult
	data object WrongCredential : ContentProtectionResult
	data class Failed(val count: Int) : ContentProtectionResult
}

/** Adds crash-safe encrypted headers on top of whichever hiding strategy is in use. */
class FolderContentProtector(
	context: Context,
	private val paths: StoragePaths,
	private val credential: () -> CharArray? = ContentCredential::copy,
	private val locker: ContentLocker = ContentLocker(),
) {
	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	suspend fun protect(path: String): ContentProtectionResult {
		val targets = targets(path) ?: return ContentProtectionResult.AccessUnavailable
		if (targets.isEmpty()) return ContentProtectionResult.NoFiles
		if (targets.size > ContentLocker.MAX_FILES) return ContentProtectionResult.Failed(1)
		val unprotected = targets.filterNot(FileLocker::isLocked)
		if (unprotected.isEmpty()) return ContentProtectionResult.Done(0)
		val password = credential() ?: return ContentProtectionResult.CredentialRequired
		return try {
			val summary = locker.lock(unprotected, password)
			if (summary.failed == 0) {
				ContentProtectionResult.Done(summary.changed)
			} else {
				// Never leave a visible folder half protected after an I/O error.
				locker.unlock(unprotected, password)
				ContentProtectionResult.Failed(summary.failed)
			}
		} finally {
			password.fill(' ')
		}
	}

	suspend fun restore(path: String): ContentProtectionResult {
		val targets = targets(path) ?: return ContentProtectionResult.AccessUnavailable
		if (targets.isEmpty() || targets.none(FileLocker::isLocked)) return ContentProtectionResult.NoFiles
		if (targets.size > ContentLocker.MAX_FILES) return ContentProtectionResult.Failed(1)
		val password = credential() ?: return ContentProtectionResult.CredentialRequired
		return try {
			val summary = locker.unlock(targets, password)
			when {
				summary.wrongPassphrase -> ContentProtectionResult.WrongCredential
				summary.failed > 0 -> ContentProtectionResult.Failed(summary.failed)
				else -> ContentProtectionResult.Done(summary.changed)
			}
		} finally {
			password.fill(' ')
		}
	}

	/**
	 * Every file under [path], or null when neither reader could see the folder.
	 *
	 * Both are tried and whichever finds files wins, because neither can be trusted to report an
	 * empty folder on its own. A File walk comes back empty for a folder this process may not
	 * enumerate, with isDirectory, canRead, canWrite and even isExternalStorageManager all saying
	 * yes; a SAF walk comes back empty when the grant does not really reach it. Taking either empty
	 * at face value reports success over every file that was never touched.
	 */
	private fun targets(path: String): List<LockTarget>? {
		val direct = File(path).takeIf(::canWalkAsFile)?.let(ContentLocker::targetsUnder)
		if (!direct.isNullOrEmpty()) return direct

		val saf = SafGrants.folder(appContext, paths, path)
			?.let { SafLockTarget.targetsUnder(resolver, it) }
		if (!saf.isNullOrEmpty()) return saf

		return saf ?: direct
	}
}
