package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.core.net.toUri
import io.github.melastore.shelf.root.StoragePaths
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
 * It is also the weakest. The files stay where they were and a file manager set to show hidden files
 * will list them; this hides a folder from someone who glances at a phone, not from someone who goes
 * looking.
 *
 * The rename has to be made through a grant on a folder *above* the target. Renaming the very folder
 * a tree grant was taken on leaves that grant pointing at a name that no longer exists, and the way
 * back would be gone with it.
 */
class DotRenameHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
) : HideStrategy {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	override val method = HideMethod.DOT_RENAME

	/** Nothing to check: this is what is left when neither of the other two is available. */
	override suspend fun isAvailable(): Boolean = true

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val name = SafPaths.nameOf(target.emulatedPath)
		if (name.startsWith(".")) {
			return@withContext HideResult.Failed("${target.displayName} is already hidden")
		}

		val parent = SafPaths.parentOf(target.emulatedPath)
		val tree = grantCovering(target.emulatedPath)
			?: return@withContext HideResult.NeedsAccess(
				parent,
				"Shelf needs access to the folder holding ${target.displayName} to rename it",
			)

		val hiddenPath = SafPaths.sibling(target.emulatedPath, SafPaths.hiddenName(name))
		val entry = HiddenEntry(
			path = target.emulatedPath,
			displayName = target.displayName,
			hiddenAt = System.currentTimeMillis(),
			method = method,
			hiddenPath = hiddenPath,
			// The covering grant, not whatever the user last picked: this is what a restore reopens.
			treeUri = tree.toString(),
		)
		if (!journal.addNew(entry)) {
			return@withContext HideResult.Failed("${target.displayName} is already hidden")
		}

		val document = documentUri(tree, target.emulatedPath)
			?: return@withContext fail(entry, "${target.emulatedPath} is not on this user's storage")

		// Listed before the rename: afterwards these are the paths MediaStore still believes the files
		// are at, which is exactly what has to be corrected.
		val contents = childPaths(tree, target.emulatedPath)

		val renamed = runCatching {
			DocumentsContract.renameDocument(resolver, document, SafPaths.hiddenName(name))
		}.getOrNull() ?: return@withContext fail(entry, "could not rename ${target.displayName}")
		val finalName = DocumentFile.fromSingleUri(appContext, renamed)?.name
			?: return@withContext rollbackRename(entry, renamed, name, "could not verify the hidden name")
		val finalEntry = entry.copy(hiddenPath = SafPaths.sibling(target.emulatedPath, finalName))
		if (!journal.replace(finalEntry)) {
			return@withContext rollbackRename(entry, renamed, name, "could not update the recovery journal")
		}

		markNoMedia(renamed)
		MediaStorePurge.scan(appContext, contents + target.emulatedPath)
		val warning = "the document provider renamed it to $finalName"
			.takeIf { finalName != SafPaths.hiddenName(name) }
		HideResult.Ok(finalEntry, warning)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		// The grant recorded at hide time, while it is still held; failing that, any grant the user has
		// given since that reaches the folder.
		val tree = heldTree(entry.treeUri) ?: grantCovering(entry.hiddenPath)
			?: return@withContext HideResult.NeedsAccess(
				SafPaths.parentOf(entry.path),
				"Shelf needs access to the folder holding ${entry.displayName} to rename it back",
			)
		val document = documentUri(tree, entry.hiddenPath)
			?: return@withContext HideResult.Failed("${entry.hiddenPath} is not on this user's storage")

		val restored = runCatching {
			DocumentsContract.renameDocument(resolver, document, SafPaths.nameOf(entry.path))
		}.getOrNull() ?: return@withContext HideResult.Failed("could not rename ${entry.displayName} back")
		val restoredName = DocumentFile.fromSingleUri(appContext, restored)?.name
			?: return@withContext HideResult.Failed("could not verify the restored folder name")
		val restoredPath = SafPaths.sibling(entry.path, restoredName)

		journal.remove(entry.path)
		MediaStorePurge.scan(appContext, childPaths(tree, restoredPath) + restoredPath)
		val warning = "restored as $restoredName because the original name was unavailable"
			.takeIf { restoredName != SafPaths.nameOf(entry.path) }
		HideResult.Ok(entry, warning)
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

	private suspend fun fail(entry: HiddenEntry, reason: String): HideResult {
		journal.remove(entry.path)
		return HideResult.Failed(reason)
	}

	private suspend fun rollbackRename(
		entry: HiddenEntry,
		renamed: Uri,
		originalName: String,
		reason: String,
	): HideResult {
		runCatching { DocumentsContract.renameDocument(resolver, renamed, originalName) }
		journal.remove(entry.path)
		return HideResult.Failed(reason)
	}

	private fun documentUri(tree: Uri, path: String): Uri? {
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
	}

	/** Paths of the files under a folder, so the scanner can be told which ones moved. */
	private fun childPaths(tree: Uri, path: String, depth: Int = 0): List<String> {
		if (depth > MAX_DEPTH) return emptyList()
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return emptyList()
		val children = runCatching {
			DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
		}.getOrNull() ?: return emptyList()

		val found = mutableListOf<String>()
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
						found += childPaths(tree, childPath, depth + 1)
					} else {
						found += childPath
					}
				}
			}
		}
		return found
	}

	/**
	 * Belt and braces on top of the dot: some galleries index dot-directories anyway. The provider
	 * decides the final name and may add an extension to it, in which case the file is no use as a
	 * marker and is removed again.
	 */
	private fun markNoMedia(folder: Uri) {
		val created = runCatching {
			DocumentsContract.createDocument(resolver, folder, "application/octet-stream", NO_MEDIA)
		}.getOrNull() ?: return
		val name = DocumentFile.fromSingleUri(appContext, created)?.name
		if (name != NO_MEDIA) runCatching { DocumentsContract.deleteDocument(resolver, created) }
	}

	private companion object {
		const val NO_MEDIA = ".nomedia"
		const val MAX_DEPTH = 8
		const val MAX_CHILDREN = 5_000
	}
}
