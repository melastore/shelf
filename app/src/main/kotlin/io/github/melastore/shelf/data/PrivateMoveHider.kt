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
 * A file manager with all-files access and hidden files enabled can still find the vault. Copying is
 * never a fallback: it would be slow and could leave a half-written duplicate.
 */
class PrivateMoveHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
) : HideStrategy {

	private val appContext = context.applicationContext

	override val method = HideMethod.PRIVATE_MOVE

	override suspend fun isAvailable(): Boolean = Environment.isExternalStorageManager()

	/**
	 * The vault stays in shared storage so uninstalling Shelf cannot delete the user's folders.
	 * The random child name avoids collisions and the dot keeps ordinary file managers out of it.
	 */
	private fun vaults(): List<File> = listOf(File(paths.emulatedRoot, ".shelf"))

	override suspend fun hide(target: FolderTarget): HideResult = withContext(Dispatchers.IO) {
		val source = File(target.emulatedPath)
		if (!source.isDirectory || !isSafeOrigin(target.emulatedPath)) {
			return@withContext HideResult.Failed("${target.displayName} is not a folder Shelf can read")
		}

		// Listed before the move, because afterwards there is nothing at these paths to enumerate.
		val contents = MediaStorePurge.listFiles(target.emulatedPath)

		for (vault in vaults()) {
			val destination = File(vault, "${UUID.randomUUID()}/${source.name}")
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
				return@withContext HideResult.Failed("${target.displayName} is already hidden")
			}

			if (source.renameTo(destination)) {
				MediaStorePurge.scan(appContext, contents + target.emulatedPath)
				return@withContext HideResult.Ok(entry)
			}

			journal.remove(entry.path)
			destination.parentFile?.deleteRecursively()
		}

		HideResult.Failed(
			"could not move ${target.displayName} without copying it, which would be slow enough to notice",
		)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		val hidden = File(entry.hiddenPath)
		val original = File(entry.path)

		// A crash between journalling and the rename leaves the folder where it started. Nothing to
		// move back, so just drop the record rather than reporting a failure the user cannot act on.
		if (!hidden.exists()) {
			if (!original.exists()) {
				return@withContext HideResult.Failed("${entry.displayName} is no longer at ${entry.hiddenPath}")
			}
			journal.remove(entry.path)
			cleanupRecoveryDirectory(hidden)
			return@withContext HideResult.Ok(entry)
		}

		original.parentFile?.mkdirs()
		if (!hidden.renameTo(original)) {
			return@withContext HideResult.Failed("could not move ${entry.displayName} back to ${entry.path}")
		}
		cleanupRecoveryDirectory(hidden)

		journal.remove(entry.path)
		val warning = MediaStorePurge.rescan(appContext, entry.path)
		HideResult.Ok(entry, warning)
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
					displayName = hidden.name,
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

	private companion object {
		const val ORIGIN_MARKER = ".shelf-origin-v1"
		const val MAX_MARKER_BYTES = 16_384L
		const val MAX_RECOVERY_DIRECTORIES = 1_000
	}
}
