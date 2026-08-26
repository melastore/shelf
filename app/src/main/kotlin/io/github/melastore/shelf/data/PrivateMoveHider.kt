package io.github.melastore.shelf.data

import android.content.Context
import android.os.Environment
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hides folders by moving them into a dedicated dot-directory on shared storage.
 *
 * With all-files access the app gets ordinary File access to the volume, and a move within one
 * volume is a rename: one syscall, no data copied, the same cost for four photos as for forty
 * gigabytes of video. The destination remains outside app-specific storage so Android will not
 * delete the user's folders if Shelf is uninstalled.
 *
 * A file manager with all-files access and hidden files enabled can still find the vault, so file
 * names and headers are protected before the move when the primary PIN is present. Copying is never a
 * fallback: it would be slow and could leave a half-written duplicate.
 */
class PrivateMoveHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
	private val allFilesAvailable: () -> Boolean = Environment::isExternalStorageManager,
	private val mediaIndex: FolderMediaIndex = MediaStorePurge,
) : HideStrategy {

	private val appContext = context.applicationContext

	@Volatile private var sharedRootWritable = false
	private val content = FolderContentProtector(appContext, paths)
	private val names = FolderNameProtector(appContext, paths)

	override val method = HideMethod.PRIVATE_MOVE

	override suspend fun isAvailable(): Boolean = allFilesAvailable() && canWriteSharedRoot()

	/**
	 * Whether a folder can really be moved into shared storage.
	 *
	 * [allFilesAvailable] is not the answer on its own. On some builds `isExternalStorageManager`
	 * reports true for an app that only declares the permission, while the storage mount the process
	 * was actually given lists every folder as empty and refuses every write. Believing it puts
	 * "Full storage access granted" on the setup screen and lets Automatic pick a method that cannot
	 * work, so the capability is tried rather than asked about.
	 *
	 * The probe is a dot-named directory removed immediately, never the vault itself: creating
	 * `.shelf` to answer a question would leave Shelf's own fingerprint in storage for someone who
	 * may never use this method. A success is remembered because it cannot become false without the
	 * permission change that restarts the process; a failure is not, so granting access mid-session
	 * is noticed.
	 */
	private suspend fun canWriteSharedRoot(): Boolean = withContext(Dispatchers.IO) {
		if (sharedRootWritable) return@withContext true
		val probe = File(paths.emulatedRoot, ".${UUID.randomUUID()}")
		val made = runCatching { probe.mkdir() }.getOrDefault(false)
		runCatching { probe.delete() }
		if (made) sharedRootWritable = true
		made
	}

	/**
	 * The vault stays in shared storage so uninstalling Shelf cannot delete the user's folders.
	 * Random container and payload names avoid exposing the original folder name at first glance. The
	 * dot keeps ordinary file managers out of it unless their hidden-file option is enabled.
	 */
	private fun vaults(): List<File> = listOf(File(paths.emulatedRoot, ".shelf"))

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val source = File(target.emulatedPath)
		if (!source.isDirectory || !isSafeOrigin(target.emulatedPath)) {
			return@withContext HideResult.Failed(HideFailure.FolderUnreadable(target.displayName))
		}

		// Listed before the move, because afterwards there is nothing at these paths to enumerate.
		val contents = mediaIndex.listFiles(target.emulatedPath)

		for (vault in vaults()) {
			val destination = File(vault, "${UUID.randomUUID()}/.$PAYLOAD_PREFIX${UUID.randomUUID()}")
			val recoveryMarker = File(destination.parentFile, ORIGIN_MARKER)
			val entry = HiddenEntry(
				path = target.emulatedPath,
				displayName = target.displayName,
				hiddenAt = System.currentTimeMillis(),
				method = method,
				hiddenPath = destination.path,
			)
			destination.parentFile?.mkdirs()
			markNoMedia(vault)
			if (!writeOrigin(recoveryMarker, target.emulatedPath)) {
				destination.parentFile?.deleteRecursively()
				continue
			}

			// The journal is the normal route back; the marker is the last-resort route after app data is
			// cleared or the app is reinstalled. Both exist before the folder moves.
			if (!journal.addNew(entry)) {
				destination.parentFile?.deleteRecursively()
				return@withContext HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
			}

			val protectionWarning = when (val protected = content.protect(source.path)) {
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
			val nameWarning = when (val protected = names.protect(source.path)) {
				is NameProtectionResult.Done, NameProtectionResult.NoFiles -> null

				NameProtectionResult.AccessUnavailable,
				NameProtectionResult.CredentialRequired -> HideWarning.NameProtectionUnavailable

				NameProtectionResult.WrongCredential -> {
					names.restore(source.path)
					content.restore(source.path)
					journal.remove(entry.path)
					destination.parentFile?.deleteRecursively()
					return@withContext HideResult.Failed(HideFailure.ContentCredentialIncorrect)
				}

				is NameProtectionResult.Failed -> {
					names.restore(source.path)
					content.restore(source.path)
					journal.remove(entry.path)
					destination.parentFile?.deleteRecursively()
					return@withContext HideResult.Failed(HideFailure.NameProtectionFailed(protected.count))
				}
			}

			if (source.renameTo(destination)) {
				mediaIndex.scan(appContext, contents + target.emulatedPath)
				return@withContext HideResult.Ok(entry, mergeWarnings(protectionWarning, nameWarning))
			}

			names.restore(source.path)
			content.restore(source.path)
			journal.remove(entry.path)
			destination.parentFile?.deleteRecursively()
		}

		HideResult.Failed(HideFailure.MoveFailed(target.displayName))
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		if (!isAvailable()) {
			return@withContext HideResult.Failed(HideFailure.AllFilesRequired(entry.displayName))
		}
		val hidden = File(entry.hiddenPath)
		val original = File(entry.path)

		// A crash between journalling and the rename leaves the folder where it started. Nothing to
		// move back, so just drop the record rather than reporting a failure the user cannot act on.
		if (!hidden.exists()) {
			if (!original.exists()) {
				return@withContext HideResult.Failed(
					HideFailure.HiddenFolderMissing(entry.displayName, entry.hiddenPath),
				)
			}
			when (val restored = names.restore(original.path)) {
				is NameProtectionResult.Done, NameProtectionResult.NoFiles -> Unit

				NameProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialRequired,
				)

				NameProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialIncorrect,
				)

				is NameProtectionResult.Failed -> return@withContext HideResult.Failed(
					HideFailure.NameRestoreFailed(restored.count),
				)

				NameProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
					HideFailure.AllFilesRequired(entry.displayName),
				)
			}
			when (val restored = content.restore(original.path)) {
				is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

				ContentProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialRequired,
				)

				ContentProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
					HideFailure.ContentCredentialIncorrect,
				)

				is ContentProtectionResult.Failed -> return@withContext HideResult.Failed(
					HideFailure.ContentRestoreFailed(restored.count),
				)

				ContentProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
					HideFailure.AllFilesRequired(entry.displayName),
				)
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
		when (val restored = names.restore(hidden.path)) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> Unit

			NameProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialRequired,
			)

			NameProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialIncorrect,
			)

			is NameProtectionResult.Failed -> return@withContext HideResult.Failed(
				HideFailure.NameRestoreFailed(restored.count),
			)

			NameProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
				HideFailure.AllFilesRequired(entry.displayName),
			)
		}
		when (val restored = content.restore(hidden.path)) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

			ContentProtectionResult.CredentialRequired -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialRequired,
			)

			ContentProtectionResult.WrongCredential -> return@withContext HideResult.Failed(
				HideFailure.ContentCredentialIncorrect,
			)

			is ContentProtectionResult.Failed -> return@withContext HideResult.Failed(
				HideFailure.ContentRestoreFailed(restored.count),
			)

			ContentProtectionResult.AccessUnavailable -> return@withContext HideResult.Failed(
				HideFailure.AllFilesRequired(entry.displayName),
			)
		}
		original.parentFile?.mkdirs()
		if (!hidden.renameTo(original)) {
			content.protect(hidden.path)
			names.protect(hidden.path)
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
				if (marker == null || readOrigin(marker) != entry.path) {
					HiddenHealth(HiddenHealthStatus.RECOVERY_DAMAGED, HiddenHealthDetail.MOVE_MARKER_DAMAGED)
				} else {
					HiddenHealth(HiddenHealthStatus.HEALTHY, HiddenHealthDetail.MOVE_INTACT)
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
				val hidden = directory.listFiles()?.singleOrNull {
					it.isDirectory && it.name != ORIGIN_MARKER
				} ?: continue
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

	/** Keeps the vault itself out of the index, in case its contents are ever scannable. */
	private fun markNoMedia(vault: File) {
		vault.mkdirs()
		val marker = File(vault, ".nomedia")
		if (!marker.exists()) runCatching { marker.createNewFile() }
	}

	private fun writeOrigin(marker: File, path: String): Boolean = runCatching {
		val encoded = Base64.getUrlEncoder().withoutPadding()
			.encodeToString(path.toByteArray(Charsets.UTF_8))
		FileOutputStream(marker).use { output ->
			output.write(encoded.toByteArray(Charsets.US_ASCII))
			output.fd.sync()
		}
	}.isSuccess

	private fun readOrigin(marker: File): String? {
		if (!marker.isFile || marker.length() !in 1..MAX_MARKER_BYTES) return null
		return runCatching {
			Base64.getUrlDecoder().decode(marker.readText(Charsets.US_ASCII))
				.toString(Charsets.UTF_8)
		}.getOrNull()
	}

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

	private fun mergeWarnings(first: HideWarning?, second: HideWarning?): HideWarning? = when {
		first == null -> second
		second == null -> first
		else -> HideWarning.Multiple(listOf(first, second))
	}

	private companion object {
		const val ORIGIN_MARKER = ".shelf-origin-v1"
		const val PAYLOAD_PREFIX = "payload-"
		const val MAX_MARKER_BYTES = 16_384L
		const val MAX_RECOVERY_DIRECTORIES = 1_000
	}
}
