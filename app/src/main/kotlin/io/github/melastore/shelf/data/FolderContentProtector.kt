package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths
import java.io.File

/**
 * The primary PIN is retained only while the real private space is open. It lets an ordinary hide
 * protect file headers without asking for a second password, and is wiped with every vault lock.
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

	/** Keeps a short-lived copy alive while an emergency hide closes the UI immediately. */
	@Synchronized fun retain(): AutoCloseable {
		val snapshot = value?.copyOf() ?: return AutoCloseable { }
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

/** Adds crash-safe encrypted headers to the existing instant folder-hiding strategies. */
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
				// Do not leave an unhidden folder half protected after an I/O error.
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

	private fun targets(path: String): List<LockTarget>? {
		val direct = File(path)
		if (direct.isDirectory && direct.canRead() && direct.canWrite()) {
			return ContentLocker.targetsUnder(direct)
		}

		val tree = coveringGrant(path) ?: return null
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		val uri = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
			?: return null
		val folder = DocumentFile.fromSingleUri(appContext, uri)?.takeIf { it.isDirectory } ?: return null
		return SafLockTarget.targetsUnder(resolver, folder)
	}

	private fun coveringGrant(path: String): Uri? = resolver.persistedUriPermissions.asSequence()
		.filter { it.isReadPermission && it.isWritePermission }
		.mapNotNull { permission ->
			val id = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
				?: return@mapNotNull null
			val relative = id.removePrefix(PRIMARY)
			if (relative == id) return@mapNotNull null
			val root = paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
			root to permission.uri
		}
		.filter { (root, _) -> path == root || path.startsWith("$root/") }
		.maxByOrNull { (root, _) -> root.length }
		?.second

	private companion object {
		const val PRIMARY = "primary:"
	}
}
