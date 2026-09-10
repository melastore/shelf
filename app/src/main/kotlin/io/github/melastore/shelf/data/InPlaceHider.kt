package io.github.melastore.shelf.data

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hides a folder without moving it: the contents are protected where they lie.
 *
 * For the folders [DotRenameHider] cannot touch. A rename is authorised by the folder above the
 * target, and the provider refuses a grant on the volume root, so a folder sitting directly in it
 * can never be renamed without all-files access. A grant on the folder *itself* the picker does
 * give, and that is enough to rename everything inside it.
 *
 * The folder keeps its name, which is the whole cost of this method. What goes is the content: file
 * names are replaced from the credential-encrypted manifest, headers are encrypted, and a .nomedia
 * keeps the scanner and every gallery out. Anyone browsing storage still sees the folder.
 *
 * Nothing about it is one-way. The grant is on a path that does not change, so it stays valid
 * through both directions however many times the folder is hidden and brought back.
 */
class InPlaceHider(context: Context, private val journal: Journal, private val paths: StoragePaths,) : HideStrategy {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver
	private val content = FolderContentProtector(appContext, paths)
	private val names = FolderNameProtector(appContext, paths)

	override val method = HideMethod.IN_PLACE

	/** Needs no permission of its own, only a grant on the folder, which [hide] asks for. */
	override suspend fun isAvailable(): Boolean = true

	/** Never blocked. A missing grant is something the picker can still supply. */
	override suspend fun blocker(path: String): HideFailure? = null

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val path = target.emulatedPath
		if (SafPaths.documentId(paths.emulatedRoot, path) == null) {
			return@withContext HideResult.Failed(HideFailure.NotPrimaryStorage(path))
		}
		val root = SafGrants.folder(appContext, paths, path)
			?: return@withContext HideResult.NeedsAccess(path, target.displayName)
		if (root.findFile(MARKER)?.isFile == true) {
			return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
		}
		val tree = SafGrants.covering(resolver, paths.emulatedRoot, path)
			?: return@withContext HideResult.NeedsAccess(path, target.displayName)

		val entry = HiddenEntry(
			path = path,
			displayName = target.displayName,
			hiddenAt = System.currentTimeMillis(),
			method = method,
			// Nothing moves, so the folder is its own hidden location.
			hiddenPath = path,
			treeUri = tree.toString(),
		)
		if (!journal.addNew(entry)) {
			return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
		}
		// Read before the names change: these are the MediaStore rows that go stale.
		val contents = SafGrants.childPaths(resolver, paths.emulatedRoot, tree, path, MAX_DEPTH, MAX_CHILDREN)

		val protectionWarning = when (val protected = content.protect(path)) {
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
		val nameWarning = when (val protected = names.protect(path)) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> null

			NameProtectionResult.AccessUnavailable,
			NameProtectionResult.CredentialRequired -> HideWarning.NameProtectionUnavailable

			NameProtectionResult.WrongCredential -> {
				names.restore(path)
				content.restore(path)
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is NameProtectionResult.Failed -> {
				names.restore(path)
				content.restore(path)
				journal.remove(entry.path)
				return@withContext HideResult.Failed(HideFailure.NameProtectionFailed(protected.count))
			}
		}

		// No second-layer setting here: protecting the contents is the whole hide, and skipping it
		// would leave the folder exactly as it was found.
		val recorded = entry.copy(deep = protectionWarning == null && nameWarning == null)
		if (!recorded.deep) journal.replace(recorded)

		// Written last, so a hide killed partway leaves no marker claiming work that did not finish.
		// Whether the .nomedia was ours is recorded in it: one the owner put there is theirs to keep.
		val ourNoMedia = root.findFile(NO_MEDIA) == null && createEmpty(root, NO_MEDIA)
		if (!writeMarker(root, ourNoMedia)) {
			names.restore(path)
			content.restore(path)
			if (ourNoMedia) deleteChild(root, NO_MEDIA)
			journal.remove(entry.path)
			return@withContext HideResult.Failed(HideFailure.RecoveryDataCreateFailed(target.displayName))
		}

		MediaStorePurge.scan(appContext, contents + path)
		HideResult.Ok(
			recorded,
			mergeWarnings(HideWarning.FolderNameVisible, protectionWarning, nameWarning),
		)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		val path = entry.path
		val root = SafGrants.folder(appContext, paths, path)
			?: return@withContext HideResult.NeedsAccess(path, entry.displayName)

		// Before the names go back, not after. Name protection treats a .nomedia the owner put there
		// as content and renames it, so ours has to be gone by the time that one is restored or the
		// provider hands the owner's back under a name it invented to dodge the collision.
		if (markedOurNoMedia(root)) deleteChild(root, NO_MEDIA)

		when (val nameRestore = names.restore(path)) {
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
				path,
				entry.displayName,
			)
		}
		when (val contentRestore = content.restore(path)) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

			ContentProtectionResult.CredentialRequired -> {
				names.protect(path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialRequired)
			}

			ContentProtectionResult.WrongCredential -> {
				names.protect(path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is ContentProtectionResult.Failed -> {
				names.protect(path)
				return@withContext HideResult.Failed(HideFailure.ContentRestoreFailed(contentRestore.count))
			}

			ContentProtectionResult.AccessUnavailable -> {
				names.protect(path)
				return@withContext HideResult.NeedsAccess(path, entry.displayName)
			}
		}

		deleteChild(root, MARKER)
		journal.remove(entry.path)
		val warning = MediaStorePurge.rescan(appContext, path)
		HideResult.Ok(entry, warning)
	}

	override suspend fun health(entry: HiddenEntry): HiddenHealth = withContext(Dispatchers.IO) {
		val root = SafGrants.folder(appContext, paths, entry.path)
			?: return@withContext HiddenHealth(
				HiddenHealthStatus.ACCESS_REQUIRED,
				HiddenHealthDetail.SAF_ACCESS_REQUIRED,
			)
		when {
			root.findFile(MARKER)?.isFile == true -> HiddenHealth(
				HiddenHealthStatus.HEALTHY,
				HiddenHealthDetail.IN_PLACE_INTACT,
			)

			// The folder is there and the marker is not, so the contents are back in the open.
			root.isDirectory -> HiddenHealth(
				HiddenHealthStatus.ALREADY_RESTORED,
				HiddenHealthDetail.IN_PLACE_ALREADY_RESTORED,
			)

			else -> HiddenHealth(HiddenHealthStatus.MISSING, HiddenHealthDetail.IN_PLACE_MISSING)
		}
	}

	private fun writeMarker(root: DocumentFile, ourNoMedia: Boolean): Boolean {
		if (root.findFile(MARKER) != null && !deleteChild(root, MARKER)) return false
		val body = if (ourNoMedia) OWNS_NO_MEDIA else KEEPS_NO_MEDIA
		return createExactly(root, MARKER, body.toByteArray())
	}

	/**
	 * Creates [name] under exactly that name.
	 *
	 * The provider appends an extension matching the mime type to a name that has none, so
	 * `.nomedia` was arriving as `.nomedia.txt`: inert to the media scanner, and a marker under a
	 * name nothing looks for. Creating under a name that already carries an extension and renaming
	 * after is what the name manifest does, and a rename adds nothing.
	 */
	private fun createExactly(root: DocumentFile, name: String, body: ByteArray): Boolean {
		deleteChild(root, TEMP)
		val temporary = runCatching { root.createFile(MIME, TEMP) }.getOrNull() ?: return false
		val written = runCatching {
			resolver.openFileDescriptor(temporary.uri, "rwt")?.use { descriptor ->
				FileOutputStream(descriptor.fileDescriptor).use { output ->
					output.write(body)
					output.fd.sync()
				}
			} != null
		}.getOrDefault(false)
		if (!written) {
			deleteChild(root, TEMP)
			return false
		}
		val renamed = runCatching {
			DocumentsContract.renameDocument(resolver, temporary.uri, name)
		}.getOrNull() ?: run {
			deleteChild(root, TEMP)
			return false
		}
		return DocumentFile.fromSingleUri(appContext, renamed)?.name == name
	}

	private fun markedOurNoMedia(root: DocumentFile): Boolean {
		val marker = root.findFile(MARKER)?.takeIf { it.isFile } ?: return false
		// openInputStream comes back null on this document however the provider is asked, so the
		// marker is read the way the name manifest is: a descriptor, then a plain stream over it.
		val text = runCatching {
			resolver.openUnredacted(marker.uri)?.use { descriptor ->
				FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()) }
			}
		}.getOrNull() ?: return false
		return text.trim() == OWNS_NO_MEDIA
	}

	private fun createEmpty(root: DocumentFile, name: String): Boolean = createExactly(root, name, ByteArray(0))

	private fun deleteChild(root: DocumentFile, name: String): Boolean {
		val child = root.findFile(name) ?: return true
		return runCatching { DocumentsContract.deleteDocument(resolver, child.uri) }.getOrDefault(false)
	}

	private companion object {
		// Prefixed so the protectors treat it as Shelf's own file rather than content to rename.
		const val MARKER = SHELF_FILE_PREFIX + "inplace-v1"
		const val NO_MEDIA = ".nomedia"
		const val MIME = "application/octet-stream"
		const val TEMP = SHELF_FILE_PREFIX + "inplace.tmp"
		const val OWNS_NO_MEDIA = "nomedia=shelf"
		const val KEEPS_NO_MEDIA = "nomedia=kept"
		const val MAX_DEPTH = 8
		const val MAX_CHILDREN = 5_000
	}
}
