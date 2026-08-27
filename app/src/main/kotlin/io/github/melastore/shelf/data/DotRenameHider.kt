package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hides folders by renaming them to a leading dot, using nothing but grants the user has given.
 *
 * This is the fallback that always works: no root, no all-files access, nothing in the app's
 * permission list to explain. A rename through the document provider is one syscall underneath, so
 * it is as fast as the other two, and the media scanner skips dot-directories, which takes the
 * folder out of every gallery on the device.
 *
 * It remains the easiest folder to locate: a file manager set to show hidden files can list it.
 * [FolderNameProtector] replaces visible file names, and [FolderContentProtector] prevents ordinary
 * opening and previews after a primary-PIN hide. The rest of each file remains in place and is not
 * full-file encryption.
 *
 * The rename has to be made through a grant on a folder *above* the target. Renaming the very folder
 * a tree grant was taken on leaves that grant pointing at a name that no longer exists, and the way
 * back would be gone with it.
 *
 * Some parents can never be granted: the provider marks the volume root and Download with
 * FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE, so the picker refuses them however many times it is shown. A
 * folder sitting directly in one of those is renamed through the file system instead, which needs
 * all-files access. Asking for a grant that cannot be given is the one thing not done here.
 */
class DotRenameHider(context: Context, private val journal: Journal, private val paths: StoragePaths,) : HideStrategy {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver
	private val content = FolderContentProtector(appContext, paths)
	private val names = FolderNameProtector(appContext, paths)

	override val method = HideMethod.DOT_RENAME

	/** Nothing to check: this is what is left when neither of the other two is available. */
	override suspend fun isAvailable(): Boolean = true

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val name = SafPaths.nameOf(target.emulatedPath)
		if (name.startsWith(".")) {
			return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
		}

		if (SafPaths.documentId(paths.emulatedRoot, target.emulatedPath) == null) {
			return@withContext HideResult.Failed(HideFailure.NotPrimaryStorage(target.emulatedPath))
		}
		val parent = SafPaths.parentOf(target.emulatedPath)
		val tree = grantCovering(target.emulatedPath)
		if (tree == null && grantable(parent)) {
			return@withContext HideResult.NeedsAccess(parent, target.displayName)
		}

		val hiddenPath = SafPaths.sibling(target.emulatedPath, SafPaths.hiddenName(name))
		val entry = HiddenEntry(
			path = target.emulatedPath,
			displayName = target.displayName,
			hiddenAt = System.currentTimeMillis(),
			method = method,
			hiddenPath = hiddenPath,
			// The covering grant, not whatever the user last picked: this is what a restore reopens.
			// Empty when the folder is reached through the mount, which needs no grant to reopen.
			treeUri = tree?.toString().orEmpty(),
		)
		if (!journal.addNew(entry)) {
			return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
		}
		// Capture the public names before filename protection replaces them. These are the stale
		// MediaStore rows that need invalidating after the folder itself is renamed.
		val contents = childPaths(tree, target.emulatedPath)
		val protectionWarning = when (val protected = content.protect(target.emulatedPath)) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> null

			ContentProtectionResult.AccessUnavailable,
			ContentProtectionResult.CredentialRequired -> HideWarning.ContentProtectionUnavailable

			ContentProtectionResult.WrongCredential -> {
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is ContentProtectionResult.Failed -> {
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.ContentProtectionFailed(protected.count))
			}
		}
		val nameWarning = when (val protected = names.protect(target.emulatedPath)) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> null

			NameProtectionResult.AccessUnavailable,
			NameProtectionResult.CredentialRequired -> HideWarning.NameProtectionUnavailable

			NameProtectionResult.WrongCredential -> {
				names.restore(target.emulatedPath)
				content.restore(target.emulatedPath)
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is NameProtectionResult.Failed -> {
				names.restore(target.emulatedPath)
				content.restore(target.emulatedPath)
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.NameProtectionFailed(protected.count))
			}
		}

		val renamed = renameFolder(tree, target.emulatedPath, SafPaths.hiddenName(name)) ?: run {
			names.restore(target.emulatedPath)
			content.restore(target.emulatedPath)
			return@withContext fail(entry, renameFailure(tree, target.displayName))
		}
		val finalName = renamed.name
			?: return@withContext rollbackRename(entry, renamed.uri, name, HideFailure.HiddenNameUnverified)
		val finalEntry = entry.copy(hiddenPath = SafPaths.sibling(target.emulatedPath, finalName))
		if (!journal.replace(finalEntry)) {
			return@withContext rollbackRename(entry, renamed.uri, name, HideFailure.JournalUpdateFailed)
		}

		MediaStorePurge.scan(appContext, contents + target.emulatedPath)
		val renameWarning = HideWarning.ProviderRenamed(finalName)
			.takeIf { finalName != SafPaths.hiddenName(name) }
		HideResult.Ok(
			finalEntry,
			mergeWarnings(mergeWarnings(protectionWarning, nameWarning), renameWarning),
		)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		// The grant recorded at hide time, while it is still held; failing that, any grant the user has
		// given since that reaches the folder.
		val tree = heldTree(entry.treeUri) ?: grantCovering(entry.hiddenPath)
		if (tree == null && grantable(SafPaths.parentOf(entry.path))) {
			return@withContext HideResult.NeedsAccess(SafPaths.parentOf(entry.path), entry.displayName)
		}
		if (SafPaths.documentId(paths.emulatedRoot, entry.hiddenPath) == null) {
			return@withContext HideResult.Failed(HideFailure.NotPrimaryStorage(entry.hiddenPath))
		}

		// Already back under its own name — a rename that succeeded after the journal write failed, or
		// a restore the user finished in a file manager. Clear the record rather than reporting an
		// error the user has no way to act on.
		if (!exists(tree, entry.hiddenPath) && exists(tree, entry.path)) {
			when (val nameRestore = names.restore(entry.path)) {
				is NameProtectionResult.Done, NameProtectionResult.NoFiles -> Unit

				NameProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialRequired,
				)

				NameProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialIncorrect,
				)

				is NameProtectionResult.Failed -> return@withContext HideResult.Failed(
					HideFailure.NameRestoreFailed(nameRestore.count),
				)

				NameProtectionResult.AccessUnavailable -> return@withContext HideResult.NeedsAccess(
					SafPaths.parentOf(entry.path),
					entry.displayName,
				)
			}
			when (val contentRestore = content.restore(entry.path)) {
				is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

				ContentProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialRequired,
				)

				ContentProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialIncorrect,
				)

				is ContentProtectionResult.Failed -> return@withContext HideResult.Failed(
					HideFailure.ContentRestoreFailed(contentRestore.count),
				)

				ContentProtectionResult.AccessUnavailable -> return@withContext HideResult.NeedsAccess(
					SafPaths.parentOf(entry.path),
					entry.displayName,
				)
			}
			journal.remove(entry.path)
			return@withContext HideResult.Ok(entry)
		}
		when (val nameRestore = names.restore(entry.hiddenPath)) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> Unit

			NameProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialRequired,
			)

			NameProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialIncorrect,
			)

			is NameProtectionResult.Failed -> return@withContext HideResult.Failed(
				HideFailure.NameRestoreFailed(nameRestore.count),
			)

			NameProtectionResult.AccessUnavailable -> return@withContext HideResult.NeedsAccess(
				SafPaths.parentOf(entry.path),
				entry.displayName,
			)
		}
		when (val contentRestore = content.restore(entry.hiddenPath)) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

			ContentProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialRequired,
			)

			ContentProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialIncorrect,
			)

			is ContentProtectionResult.Failed -> return@withContext HideResult.Failed(
				HideFailure.ContentRestoreFailed(contentRestore.count),
			)

			ContentProtectionResult.AccessUnavailable -> return@withContext HideResult.NeedsAccess(
				SafPaths.parentOf(entry.path),
				entry.displayName,
			)
		}

		val wanted = SafPaths.nameOf(entry.path)
		val restored = renameFolder(tree, entry.hiddenPath, wanted) ?: run {
			content.protect(entry.hiddenPath)
			names.protect(entry.hiddenPath)
			return@withContext HideResult.Failed(HideFailure.RenameBackFailed(entry.displayName))
		}
		// The rename has already happened. Some providers hand back a uri they will not then describe,
		// and treating that as a failure would leave a journal entry pointing at the old hidden name —
		// which no longer exists, so every later attempt would fail too. Assume the name we asked for.
		val restoredName = restored.name ?: wanted
		val restoredPath = SafPaths.sibling(entry.path, restoredName)

		journal.remove(entry.path)
		MediaStorePurge.scan(appContext, childPaths(tree, restoredPath) + restoredPath)
		val warning = HideWarning.RestoredWithDifferentName(restoredName)
			.takeIf { restoredName != SafPaths.nameOf(entry.path) }
		HideResult.Ok(entry, warning)
	}

	override suspend fun health(entry: HiddenEntry): HiddenHealth = withContext(Dispatchers.IO) {
		val tree = heldTree(entry.treeUri) ?: grantCovering(entry.hiddenPath)
		if (tree == null && grantable(SafPaths.parentOf(entry.path))) {
			return@withContext HiddenHealth(
				HiddenHealthStatus.ACCESS_REQUIRED,
				HiddenHealthDetail.SAF_ACCESS_REQUIRED,
			)
		}
		val hidden = probe(tree, entry.hiddenPath)
		val original = probe(tree, entry.path)
		when {
			hidden == DocumentProbe.PRESENT && original == DocumentProbe.PRESENT -> HiddenHealth(
				HiddenHealthStatus.CONFLICT,
				HiddenHealthDetail.RENAME_CONFLICT,
			)

			hidden == DocumentProbe.PRESENT && original == DocumentProbe.MISSING -> HiddenHealth(
				HiddenHealthStatus.HEALTHY,
				HiddenHealthDetail.RENAME_INTACT,
			)

			hidden == DocumentProbe.MISSING && original == DocumentProbe.PRESENT -> HiddenHealth(
				HiddenHealthStatus.ALREADY_RESTORED,
				HiddenHealthDetail.RENAME_ALREADY_RESTORED,
			)

			hidden == DocumentProbe.MISSING && original == DocumentProbe.MISSING -> HiddenHealth(
				HiddenHealthStatus.MISSING,
				HiddenHealthDetail.RENAME_MISSING,
			)

			else -> HiddenHealth(HiddenHealthStatus.UNKNOWN, HiddenHealthDetail.SAF_UNVERIFIED)
		}
	}

	override suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = withContext(Dispatchers.IO) {
		val treeId = runCatching { DocumentsContract.getTreeDocumentId(parentTree) }.getOrNull()
			?: return@withContext emptyList()
		val parentPath = treePath(treeId) ?: return@withContext emptyList()
		val children = runCatching {
			DocumentsContract.buildChildDocumentsUriUsingTree(parentTree, treeId)
		}.getOrNull() ?: return@withContext emptyList()
		val found = mutableListOf<SafRecoveryCandidate>()
		runCatching {
			resolver.query(
				children,
				arrayOf(
					DocumentsContract.Document.COLUMN_DISPLAY_NAME,
					DocumentsContract.Document.COLUMN_MIME_TYPE,
				),
				null,
				null,
				null,
			)?.use { cursor ->
				while (cursor.moveToNext() && found.size < MAX_RECOVERY_CANDIDATES) {
					val name = cursor.getString(0) ?: continue
					if (
						cursor.getString(1) != DocumentsContract.Document.MIME_TYPE_DIR ||
						!name.startsWith(".") || name.length == 1
					) {
						continue
					}
					found += SafRecoveryCandidate(
						treeUri = parentTree.toString(),
						hiddenPath = "$parentPath/$name",
						hiddenName = name,
						suggestedName = name.removePrefix("."),
					)
				}
			}
		}
		found
	}

	override suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult =
		withContext(Dispatchers.IO) {
			val name = restoredName.trim()
			if (!SafPaths.isSafeName(name)) {
				return@withContext HideResult.Failed(HideFailure.InvalidFolderName)
			}
			val tree = runCatching { candidate.treeUri.toUri() }.getOrNull()
				?: return@withContext HideResult.Failed(HideFailure.InvalidRecoveryGrant)
			val originalPath = SafPaths.sibling(candidate.hiddenPath, name)
			if (probe(tree, originalPath) != DocumentProbe.MISSING) {
				return@withContext HideResult.Failed(HideFailure.RecoveryNameConflict(name))
			}
			val entry = HiddenEntry(
				path = originalPath,
				displayName = name,
				hiddenAt = System.currentTimeMillis(),
				method = method,
				hiddenPath = candidate.hiddenPath,
				treeUri = tree.toString(),
			)
			if (!journal.addNew(entry)) {
				return@withContext HideResult.Failed(HideFailure.RecoveryRecordExists(name))
			}
			restore(entry)
		}

	/**
	 * A persisted grant on a folder strictly above [path], if the user has already given one. Grants
	 * accumulate: allowing DCIM once to hide one album covers every album under it afterwards.
	 */
	private fun grantCovering(path: String): Uri? = resolver.persistedUriPermissions
		.asSequence()
		.filter { it.isWritePermission }
		.mapNotNull { permission ->
			val treeId = runCatching {
				DocumentsContract.getTreeDocumentId(permission.uri)
			}.getOrNull() ?: return@mapNotNull null
			treePath(treeId)?.let { it to permission.uri }
		}
		.filter { (treeRoot, _) -> path.startsWith("$treeRoot/") }
		// The closest grant, so a rename is authorised by the folder nearest to it.
		.maxByOrNull { (treeRoot, _) -> treeRoot.length }
		?.second

	/** [uri] if that exact grant is still held, so a stale record does not look like a live one. */
	private fun heldTree(uri: String): Uri? {
		val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
		return parsed.takeIf { candidate ->
			resolver.persistedUriPermissions.any { it.uri == candidate && it.isWritePermission }
		}
	}

	private fun treePath(treeDocumentId: String): String? {
		val relative = treeDocumentId.removePrefix("primary:")
		if (relative == treeDocumentId && !treeDocumentId.startsWith("primary:")) return null
		return if (relative.isEmpty()) paths.emulatedRoot else "${paths.emulatedRoot}/$relative"
	}

	private suspend fun fail(entry: HiddenEntry, failure: HideFailure): HideResult {
		journal.remove(entry.path)
		return HideResult.Failed(failure)
	}

	private suspend fun rollbackRename(
		entry: HiddenEntry,
		renamed: Uri?,
		originalName: String,
		failure: HideFailure,
	): HideResult {
		val rolledBack = renamed?.let {
			runCatching { DocumentsContract.renameDocument(resolver, it, originalName) }.getOrNull()
		}
		if (rolledBack != null) {
			names.restore(entry.path)
			content.restore(entry.path)
			journal.remove(entry.path)
			return HideResult.Failed(failure)
		}

		// The folder may now be hidden under the provider's chosen name. Keep the operation record; an
		// uncertain rollback must never turn into an untracked folder. Prefer the path encoded in the
		// returned document URI when the provider exposes it.
		val hiddenPath = renamed?.let(::documentPath) ?: entry.hiddenPath
		journal.replace(entry.copy(hiddenPath = hiddenPath))
		return HideResult.Failed(HideFailure.RollbackFailed(failure))
	}

	private fun mergeWarnings(first: HideWarning?, second: HideWarning?): HideWarning? = when {
		first == null -> second
		second == null -> first
		else -> HideWarning.Multiple(listOf(first, second))
	}

	private fun documentPath(uri: Uri): String? {
		val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
		if (!documentId.startsWith(PRIMARY_DOCUMENT)) return null
		val relative = documentId.removePrefix(PRIMARY_DOCUMENT)
		return paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
	}

	/**
	 * Renames the last segment of [path], through [tree] when a grant covers it and through the
	 * mount when none can exist. A null name means the rename happened but the result could not be
	 * read back; the caller rolls back rather than recording a name it has not seen.
	 */
	private fun renameFolder(tree: Uri?, path: String, wanted: String): Renamed? {
		if (tree == null) {
			val destination = File(SafPaths.parentOf(path), wanted)
			if (destination.exists() || !File(path).renameTo(destination)) return null
			return Renamed(wanted, null)
		}
		val document = documentUri(tree, path) ?: return null
		val uri = runCatching {
			DocumentsContract.renameDocument(resolver, document, wanted)
		}.getOrNull() ?: return null
		return Renamed(DocumentFile.fromSingleUri(appContext, uri)?.name, uri)
	}

	/** Without a grant the rename went through the mount, which is all-files access or nothing. */
	private fun renameFailure(tree: Uri?, name: String): HideFailure =
		if (tree == null) HideFailure.ParentNotGrantable(name) else HideFailure.RenameFailed(name)

	/**
	 * Whether the picker could ever return a grant covering children of [parent].
	 *
	 * ExternalStorageProvider sets FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE on the volume root and on
	 * Download, so OPEN_DOCUMENT_TREE cannot land on either. A grant *inside* one of them is no help:
	 * a rename is authorised by the folder above, and that is the blocked one. Asking anyway is what
	 * produced a picker that reappeared however often it was answered.
	 */
	private fun grantable(parent: String): Boolean {
		val clean = parent.trimEnd('/')
		return clean != paths.emulatedRoot && clean != paths.emulatedRoot + "/" + DOWNLOAD
	}

	private fun exists(tree: Uri?, path: String): Boolean {
		val uri = documentUri(tree, path) ?: return File(path).exists()
		return runCatching { DocumentFile.fromSingleUri(appContext, uri)?.exists() }.getOrNull() == true
	}

	private fun probe(tree: Uri?, path: String): DocumentProbe {
		val uri = documentUri(tree, path)
			?: return if (File(path).exists()) DocumentProbe.PRESENT else DocumentProbe.MISSING
		return try {
			resolver.query(
				uri,
				arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
				null,
				null,
				null,
			)?.use { cursor ->
				if (cursor.moveToFirst()) DocumentProbe.PRESENT else DocumentProbe.MISSING
			} ?: DocumentProbe.UNKNOWN
		} catch (_: java.io.FileNotFoundException) {
			DocumentProbe.MISSING
		} catch (_: SecurityException) {
			DocumentProbe.UNREACHABLE
		} catch (_: Exception) {
			DocumentProbe.UNKNOWN
		}
	}

	private fun documentUri(tree: Uri?, path: String): Uri? {
		if (tree == null) return null
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
	}

	/** Paths of the files under a folder, so the scanner can be told which ones moved. */
	private fun childPaths(tree: Uri?, path: String): List<String> = buildList {
		if (tree == null) {
			File(path).walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile }
				.take(MAX_CHILDREN).mapTo(this) { it.absolutePath }
			return@buildList
		}
		collectChildPaths(tree, path, depth = 0, found = this)
	}

	private fun collectChildPaths(tree: Uri, path: String, depth: Int, found: MutableList<String>) {
		if (depth > MAX_DEPTH || found.size >= MAX_CHILDREN) return
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return
		val children = runCatching {
			DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
		}.getOrNull() ?: return

		runCatching {
			resolver.query(
				children,
				arrayOf(
					DocumentsContract.Document.COLUMN_DISPLAY_NAME,
					DocumentsContract.Document.COLUMN_MIME_TYPE,
				),
				null,
				null,
				null,
			)?.use { cursor ->
				while (cursor.moveToNext() && found.size < MAX_CHILDREN) {
					val name = cursor.getString(0) ?: continue
					val childPath = "$path/$name"
					if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
						collectChildPaths(tree, childPath, depth + 1, found)
					} else {
						found += childPath
					}
				}
			}
		}
	}

	private data class Renamed(val name: String?, val uri: Uri?)

	private companion object {
		const val PRIMARY_DOCUMENT = "primary:"
		const val DOWNLOAD = "Download"
		const val MAX_DEPTH = 8
		const val MAX_CHILDREN = 5_000
		const val MAX_RECOVERY_CANDIDATES = 200
	}

	private enum class DocumentProbe { PRESENT, MISSING, UNREACHABLE, UNKNOWN }
}
