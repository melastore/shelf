package io.github.melastore.shelf.data

import android.content.Context
import android.os.Environment
import io.github.melastore.shelf.crypto.PasswordEnvelope
import io.github.melastore.shelf.root.StoragePaths
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hides folders by moving them into a dot-directory on shared storage.
 *
 * With all-files access a move within one volume is a rename: one syscall, nothing copied, the same
 * cost for four photos as for forty gigabytes of video. The destination stays outside app-specific
 * storage so uninstalling Shelf cannot take the user's folders with it.
 *
 * A file manager with all-files access and hidden files on can still find the vault, so names and
 * headers are protected before the move when the credential is available. Copying is never a
 * fallback; it would be slow and could leave a half-written duplicate.
 */
class PrivateMoveHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
	private val allFilesAvailable: () -> Boolean = {
		runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
	},
	private val mediaIndex: FolderMediaIndex = MediaStorePurge,
	private val deepProtection: () -> Boolean = { AppPreferences(context).deepProtection() },
) : HideStrategy {

	private val appContext = context.applicationContext
	private val content = FolderContentProtector(appContext, paths)
	private val names = FolderNameProtector(appContext, paths)

	override val method = HideMethod.PRIVATE_MOVE

	override suspend fun isAvailable(): Boolean = runCatching { allFilesAvailable() }.getOrDefault(false)

	/**
	 * The vault lives in shared storage so uninstalling Shelf cannot delete the user's folders.
	 * Random container and payload names keep the original folder name off the surface, and the dot
	 * keeps ordinary file managers out unless hidden files are turned on.
	 */
	private fun vaults(): List<File> = listOf(File(paths.emulatedRoot, ".shelf"))

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val source = File(target.emulatedPath)
		if (!source.isDirectory || !isSafeOrigin(target.emulatedPath)) {
			return@withContext HideResult.Failed(HideFailure.FolderUnreadable(target.displayName))
		}

		// Listed before the move: afterwards there is nothing at these paths to enumerate.
		val contents = mediaIndex.listFiles(target.emulatedPath)

		// The mount decides whether the move can happen and no permission API reports what it will do.
		// A build can answer isExternalStorageManager true, resolve the appop to allowed, and still
		// hand this process a view where the folder is empty and unrenameable. A folder known to hold
		// files that lists as empty here is that view. Refuse now, before anything has been touched.
		if (contents.isNotEmpty() && source.listFiles().isNullOrEmpty()) {
			return@withContext HideResult.Failed(HideFailure.AllFilesRequired(target.displayName))
		}

		// Off, the move is the whole hide. The entry records which it was, so a restore and the vault
		// both go by what was done rather than by what the setting says today.
		val deep = deepProtection()
		for (vault in vaults()) {
			val destination = File(vault, "${UUID.randomUUID()}/.$PAYLOAD_PREFIX${UUID.randomUUID()}")
			val recoveryMarker = File(destination.parentFile, ORIGIN_MARKER)
			val entry = HiddenEntry(
				path = target.emulatedPath,
				displayName = target.displayName,
				hiddenAt = System.currentTimeMillis(),
				method = method,
				hiddenPath = destination.path,
				deep = deep,
			)
			destination.parentFile?.mkdirs()
			markNoMedia(vault)
			if (!writeOrigin(recoveryMarker, target.emulatedPath)) {
				destination.parentFile?.deleteRecursively()
				continue
			}

			// The journal is the normal route back, the marker the last resort after app data is
			// cleared or the app reinstalled. Both exist before the folder moves.
			if (!journal.addNew(entry)) {
				destination.parentFile?.deleteRecursively()
				return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
			}

			val protectionWarning = if (!deep) {
				null
			} else {
				when (val protected = content.protect(source.path)) {
					is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> null

					ContentProtectionResult.AccessUnavailable,
					ContentProtectionResult.CredentialRequired -> HideWarning.ContentProtectionUnavailable

					ContentProtectionResult.WrongCredential -> {
						journal.remove(entry.path)
						destination.parentFile?.deleteRecursively()
						return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
					}

					is ContentProtectionResult.Failed -> {
						journal.remove(entry.path)
						destination.parentFile?.deleteRecursively()
						return@withContext HideResult.Failed(
							HideFailure.ContentProtectionFailed(protected.count),
						)
					}
				}
			}
			val nameWarning = if (!deep) {
				null
			} else {
				when (val protected = names.protect(source.path)) {
					is NameProtectionResult.Done, NameProtectionResult.NoFiles -> null

					NameProtectionResult.AccessUnavailable,
					NameProtectionResult.CredentialRequired -> HideWarning.NameProtectionUnavailable

					NameProtectionResult.WrongCredential ->
						return@withContext rollBack(entry, destination, HideFailure.ContentCredentialIncorrect)

					is NameProtectionResult.Failed ->
						return@withContext rollBack(entry, destination, HideFailure.NameProtectionFailed(protected.count))
				}
			}

			// On if either pass changed anything, like the other methods: the vault labels a folder by it.
			val recorded = entry.copy(deep = deep && (protectionWarning == null || nameWarning == null))
			if (recorded.deep != entry.deep) journal.replace(recorded)

			if (source.renameTo(destination)) {
				mediaIndex.scan(appContext, contents + target.emulatedPath)
				return@withContext HideResult.Ok(recorded, mergeWarnings(protectionWarning, nameWarning))
			}

			val failed = rollBack(entry, destination, HideFailure.MoveFailed(target.displayName))
			if (failed.failure is HideFailure.FilesNotPutBack) return@withContext failed
		}

		HideResult.Failed(HideFailure.MoveFailed(target.displayName))
	}

	/**
	 * Puts back what the per-file passes changed and drops the empty container. The record only goes
	 * when that came back clean: a partial undo keeps it, and the next hide rolls the rest back first.
	 */
	private suspend fun rollBack(entry: HiddenEntry, destination: File, failure: HideFailure): HideResult.Failed {
		val clean = undoPasses(names, content, entry.path)
		destination.parentFile?.deleteRecursively()
		if (!clean) return HideResult.Failed(HideFailure.FilesNotPutBack(failure))
		journal.remove(entry.path)
		return HideResult.Failed(failure)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		if (!isAvailable()) {
			return@withContext HideResult.Failed(HideFailure.AllFilesRequired(entry.displayName))
		}
		val hidden = File(entry.hiddenPath)
		val original = File(entry.path)

		// A crash between journalling and the rename leaves the folder where it started. Nothing to
		// move back, so drop the record rather than report a failure the user cannot act on.
		if (!hidden.exists()) {
			if (!original.exists()) {
				return@withContext HideResult.Failed(
					HideFailure.HiddenFolderMissing(entry.displayName, entry.hiddenPath),
				)
			}
			val nameRestore = names.restore(original.path)
			when (nameRestore) {
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

				NameProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
					HideFailure.AllFilesRequired(entry.displayName),
				)
			}
			when (val restored = content.restore(original.path)) {
				is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

				ContentProtectionResult.CredentialRequired -> {
					if (nameRestore is NameProtectionResult.Done) names.protect(original.path)
					return@withContext HideResult.Failed(HideFailure.ContentCredentialRequired)
				}

				ContentProtectionResult.WrongCredential -> {
					if (nameRestore is NameProtectionResult.Done) names.protect(original.path)
					return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
				}

				is ContentProtectionResult.Failed -> {
					if (nameRestore is NameProtectionResult.Done) names.protect(original.path)
					return@withContext HideResult.Failed(HideFailure.ContentRestoreFailed(restored.count))
				}

				ContentProtectionResult.AccessUnavailable -> {
					if (nameRestore is NameProtectionResult.Done) names.protect(original.path)
					return@withContext HideResult.Failed(HideFailure.AllFilesRequired(entry.displayName))
				}
			}
			journal.remove(entry.path)
			cleanupRecoveryDirectory(hidden)
			return@withContext HideResult.Ok(entry)
		}

		if (original.exists()) {
			return@withContext HideResult.Failed(
				HideFailure.DestinationExists(entry.displayName, entry.path),
			)
		}
		val nameRestore = names.restore(hidden.path)
		when (nameRestore) {
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

			NameProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
				HideFailure.AllFilesRequired(entry.displayName),
			)
		}
		val contentRestore = content.restore(hidden.path)
		when (val restored = contentRestore) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

			ContentProtectionResult.CredentialRequired -> {
				if (nameRestore is NameProtectionResult.Done) names.protect(hidden.path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialRequired)
			}

			ContentProtectionResult.WrongCredential -> {
				if (nameRestore is NameProtectionResult.Done) names.protect(hidden.path)
				return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is ContentProtectionResult.Failed -> {
				if (nameRestore is NameProtectionResult.Done) names.protect(hidden.path)
				return@withContext HideResult.Failed(HideFailure.ContentRestoreFailed(restored.count))
			}

			ContentProtectionResult.AccessUnavailable -> {
				if (nameRestore is NameProtectionResult.Done) names.protect(hidden.path)
				return@withContext HideResult.Failed(HideFailure.AllFilesRequired(entry.displayName))
			}
		}
		original.parentFile?.mkdirs()
		if (!hidden.renameTo(original)) {
			// Only what this restore undid, so a folder moved with the second layer off stays as it was.
			if (contentRestore is ContentProtectionResult.Done) content.protect(hidden.path)
			if (nameRestore is NameProtectionResult.Done) names.protect(hidden.path)
			return@withContext HideResult.Failed(
				HideFailure.MoveBackFailed(entry.displayName, entry.path),
			)
		}
		cleanupRecoveryDirectory(hidden)

		journal.remove(entry.path)
		val warning = mediaIndex.rescan(appContext, entry.path)
		HideResult.Ok(entry, warning)
	}

	override suspend fun health(entry: HiddenEntry): HiddenHealth = withContext(Dispatchers.IO) {
		if (!isAvailable()) {
			return@withContext HiddenHealth(
				HiddenHealthStatus.ACCESS_REQUIRED,
				HiddenHealthDetail.ALL_FILES_ACCESS_REQUIRED,
			)
		}
		val hidden = File(entry.hiddenPath)
		val original = File(entry.path)
		when {
			hidden.isDirectory && original.exists() -> HiddenHealth(
				HiddenHealthStatus.CONFLICT,
				HiddenHealthDetail.MOVE_CONFLICT,
			)

			hidden.isDirectory -> {
				val marker = hidden.parentFile?.let { File(it, ORIGIN_MARKER) }
				// A sealed marker cannot be read without the credential, which is not proof it is damaged.
				val intact = marker != null && when (val origin = readOrigin(marker)) {
					null -> isSealed(marker) && !ContentCredential.isAvailable()
					else -> origin == entry.path
				}
				if (intact) {
					HiddenHealth(HiddenHealthStatus.HEALTHY, HiddenHealthDetail.MOVE_INTACT)
				} else {
					HiddenHealth(HiddenHealthStatus.RECOVERY_DAMAGED, HiddenHealthDetail.MOVE_MARKER_DAMAGED)
				}
			}

			original.exists() -> HiddenHealth(
				HiddenHealthStatus.ALREADY_RESTORED,
				HiddenHealthDetail.MOVE_ALREADY_RESTORED,
			)

			else -> HiddenHealth(HiddenHealthStatus.MISSING, HiddenHealthDetail.MOVE_MISSING)
		}
	}

	override suspend fun recoverOrphans(): Int = withContext(Dispatchers.IO) {
		if (!isAvailable()) return@withContext 0

		var recovered = 0
		for (vault in vaults()) {
			val directories = vault.listFiles()?.asSequence()
				?.filter { it.isDirectory }
				?.take(MAX_RECOVERY_DIRECTORIES)
				.orEmpty()
			for (directory in directories) {
				val origin = readOrigin(File(directory, ORIGIN_MARKER)) ?: continue
				if (!isSafeOrigin(origin)) continue
				val hidden = directory.listFiles()?.singleOrNull { it.isDirectory } ?: continue
				val entry = HiddenEntry(
					path = origin,
					displayName = File(origin).name,
					hiddenAt = directory.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
					method = method,
					hiddenPath = hidden.path,
				)
				if (journal.addNew(entry)) recovered++
			}
		}
		recovered
	}

	/** Left behind by a reinstall that set a new credential. Only the old one opens them. */
	override suspend fun lockedOrphans(): Int = withContext(Dispatchers.IO) {
		if (!isAvailable() || !ContentCredential.isAvailable()) return@withContext 0
		vaults().sumOf { vault ->
			vault.listFiles()?.asSequence()
				?.filter { it.isDirectory }
				?.take(MAX_RECOVERY_DIRECTORIES)
				?.map { File(it, ORIGIN_MARKER) }
				?.count { it.isFile && isSealed(it) && readOrigin(it) == null }
				?: 0
		}
	}

	/** Keeps the vault out of the media index, in case its contents ever become scannable. */
	private fun markNoMedia(vault: File) {
		vault.mkdirs()
		val marker = File(vault, ".nomedia")
		if (!marker.exists()) runCatching { marker.createNewFile() }
	}

	override suspend fun dropRecoveryMarkers() = withContext(Dispatchers.IO) {
		for (vault in vaults()) {
			vault.listFiles()?.filter { it.isDirectory }?.forEach { File(it, ORIGIN_MARKER).delete() }
		}
	}

	/**
	 * Sealed under the credential when it is held, like the name manifest: the random container
	 * names are worth nothing if a file beside them spells out the original path. Plain Base64 is
	 * what markers from before this hold, and what a hide with no credential still writes.
	 */
	private fun writeOrigin(marker: File, path: String): Boolean = runCatching {
		val plain = path.toByteArray(Charsets.UTF_8)
		val credential = ContentCredential.copy()
		val body = try {
			if (credential != null) {
				PasswordEnvelope.encrypt(
					plain,
					credential,
					FolderNameProtector.MANIFEST_ROUNDS,
					ContentKeys.manifestSalt(appContext),
					cached = true,
				)
			} else {
				Base64.getUrlEncoder().withoutPadding().encodeToString(plain).toByteArray(Charsets.US_ASCII)
			}
		} finally {
			credential?.fill(' ')
		}
		FileOutputStream(marker).use { output ->
			output.write(body)
			output.fd.sync()
		}
	}.isSuccess

	/** The path [marker] names. Null when it is missing or damaged, or sealed and the credential is not held. */
	private fun readOrigin(marker: File): String? {
		if (!marker.isFile || marker.length() !in 1..MAX_MARKER_BYTES) return null
		val body = runCatching { marker.readBytes() }.getOrNull() ?: return null
		if (!body.startsWith(SEALED_MAGIC)) {
			return runCatching {
				Base64.getUrlDecoder().decode(body.toString(Charsets.US_ASCII)).toString(Charsets.UTF_8)
			}.getOrNull()
		}
		val credential = ContentCredential.copy() ?: return null
		return try {
			runCatching { PasswordEnvelope.decrypt(body, credential, cached = true).toString(Charsets.UTF_8) }.getOrNull()
		} finally {
			credential.fill(' ')
		}
	}

	private fun isSealed(marker: File): Boolean = runCatching {
		val head = ByteArray(SEALED_MAGIC.size)
		DataInputStream(marker.inputStream()).use { it.readFully(head) }
		head.contentEquals(SEALED_MAGIC)
	}.getOrDefault(false)

	private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
		size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

	private fun isSafeOrigin(path: String): Boolean {
		val backing = runCatching { paths.toBacking(path) }.getOrNull() ?: return false
		if (!paths.isSafeTarget(backing)) return false
		return vaults().none { path == it.path || path.startsWith("${it.path}/") }
	}

	private fun cleanupRecoveryDirectory(hidden: File) {
		val parent = hidden.parentFile ?: return
		File(parent, ORIGIN_MARKER).delete()
		parent.delete()
	}

	private companion object {
		const val ORIGIN_MARKER = ".shelf-origin-v1"
		const val PAYLOAD_PREFIX = "payload-"
		const val MAX_MARKER_BYTES = 16_384L
		val SEALED_MAGIC = "SHELFREC".toByteArray(Charsets.US_ASCII)
		const val MAX_RECOVERY_DIRECTORIES = 1_000
	}
}
