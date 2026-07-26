package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.root.StoragePaths
import java.util.Base64

sealed interface LockResult {
	data class Ok(val locked: LockedFile, val warning: String? = null) : LockResult
	data class Failed(val reason: String) : LockResult
}

sealed interface UnlockResult {
	data class Ok(val warning: String? = null) : UnlockResult
	data object WrongPassphrase : UnlockResult
	data class Failed(val reason: String) : UnlockResult
}

/**
 * Encrypts and decrypts the leading slice of a file in place.
 *
 * Only the header moves, so the cost is the same whatever the file weighs. Where the bytes are
 * reached from is [HeaderIo]'s problem: a document the user picked when Shelf is unprivileged, or
 * root when it is available and no grant covers the file. The crypto happens in [HeaderCipher]; the
 * recovery data is appended to the file so it survives reinstall, with a second working copy in
 * [HeaderRecovery] while the app is installed.
 */
class FileLocker(
	context: Context,
	private val store: VaultStore,
	private val recovery: HeaderRecovery,
	private val paths: StoragePaths = StoragePaths.forCurrentUser(),
	private val backends: List<HeaderIo> = listOf(SafHeaderIo(context), RootHeaderIo(paths)),
	private val cipher: HeaderCipher = HeaderCipher(),
	private val sliceSize: Int = DEFAULT_SLICE,
) {

	private val appContext = context.applicationContext

	suspend fun lock(
		emulatedPath: String,
		displayName: String,
		documentUri: Uri?,
		passphrase: CharArray,
	): LockResult {
		val picked = LockTarget(emulatedPath, documentUri)
		val (io, resolved) = resolvedBackends(picked).firstOrNull()
			?: return LockResult.Failed("Shelf cannot reach $displayName on this device")

		val size = io.size(resolved) ?: return LockResult.Failed("cannot read ${resolved.path}")
		val sliceLen = minOf(sliceSize.toLong(), size).toInt()
		if (sliceLen == 0) return LockResult.Failed("file is empty")

		val slice = io.readHead(resolved, sliceLen) ?: return LockResult.Failed("could not read header")
		if (slice.size != sliceLen) return LockResult.Failed("short read on header of $displayName")

		val sealed = cipher.seal(slice, passphrase)
		val trailer = LockTrailerCodec.encode(
			LockTrailer(
				cipherText = sealed.cipherText,
				salt = sealed.salt,
				nonce = sealed.nonce,
				tag = sealed.tag,
				originalSize = size,
			),
		)
		val locked = LockedFile(
			path = resolved.path,
			displayName = displayName,
			sliceLen = sliceLen,
			salt = sealed.salt.encode(),
			nonce = sealed.nonce.encode(),
			tag = sealed.tag.encode(),
			lockedAt = System.currentTimeMillis(),
			documentUri = documentUri?.toString().orEmpty(),
			originalSize = size,
			trailerLength = trailer.size,
		)

		// Record before overwriting: a crash after this is recoverable from the vault, a crash before
		// leaves the file untouched. Overwriting first could strand an unrecoverable header. Locking
		// a file that is already locked would seal the ciphertext a second time and drop the only
		// record of the first pass, so it is refused here rather than half-applied.
		if (!store.addNew(locked)) {
			return LockResult.Failed("$displayName is already locked")
		}
		try {
			recovery.save(resolved.path, sealed.cipherText)
		} catch (e: Exception) {
			store.remove(resolved.path)
			return LockResult.Failed(e.message ?: "could not save recovery data")
		}
		if (!io.append(resolved, trailer)) {
			if (io.truncate(resolved, size)) {
				store.remove(resolved.path)
				recovery.clear(resolved.path)
				return LockResult.Failed("could not attach recovery data to $displayName")
			}
			return LockResult.Ok(
				locked,
				"recovery data may be incomplete; use Restore before opening this file",
			)
		}

		if (!io.writeHead(resolved, sealed.cipherText)) {
			// A failed write may still have changed part of the target. Keep both records so Restore
			// can put the original header back from the complete recovery copy.
			return LockResult.Ok(
				locked,
				"the header write was interrupted; use Restore before opening this file",
			)
		}

		return LockResult.Ok(locked, forget(io, resolved.path))
	}

	suspend fun unlock(entry: LockedFile, passphrase: CharArray): UnlockResult {
		val target = LockTarget(entry.path, entry.documentUri.takeIf { it.isNotEmpty() }?.let(Uri::parse))
		var selected: Pair<HeaderIo, LockTarget>? = null
		var onDisk: ByteArray? = null
		for ((candidate, resolved) in resolvedBackends(target)) {
			val bytes = candidate.readHead(resolved, entry.sliceLen) ?: continue
			selected = candidate to resolved
			onDisk = bytes
			break
		}
		val (io, resolved) = selected
			?: return UnlockResult.Failed("Shelf can no longer reach ${entry.displayName}")
		if (entry.trailerLength > 0 && entry.originalSize >= 0) {
			val expectedSize = entry.originalSize + entry.trailerLength
			if (io.size(resolved) != expectedSize) {
				return UnlockResult.Failed("the file size changed after it was locked; nothing was overwritten")
			}
		}

		// If the bytes in the file no longer decrypt, either the passphrase is wrong or a write was
		// interrupted and the header is part ciphertext. The spare copy tells the two apart, and
		// repairs the second.
		val fromDisk = open(entry, onDisk!!, passphrase)
		val privateRecovery = if (fromDisk == null) recovery.load(entry.path) else null
		val trailerRecovery = if (fromDisk == null) readTrailer(io, resolved, entry)?.cipherText else null
		val plain = fromDisk ?: listOfNotNull(privateRecovery, trailerRecovery)
			.firstNotNullOfOrNull { open(entry, it, passphrase) }
			?: return UnlockResult.WrongPassphrase
		val repaired = fromDisk == null

		if (!io.writeHead(resolved, plain)) return UnlockResult.Failed("could not write header")
		if (entry.trailerLength > 0 && entry.originalSize >= 0 && !io.truncate(resolved, entry.originalSize)) {
			return UnlockResult.Failed("header restored, but recovery data could not be removed")
		}

		store.remove(entry.path)
		recovery.clear(entry.path)
		val warning = listOfNotNull(
			"the header on disk was damaged and was rebuilt from the recovery copy".takeIf { repaired },
			restoreToIndex(io, resolved.path),
		).joinToString("; ").ifEmpty { null }
		return UnlockResult.Ok(warning)
	}

	/** Restores a self-contained locked file after Shelf was reinstalled or its records were lost. */
	suspend fun recover(
		emulatedPath: String,
		displayName: String,
		documentUri: Uri?,
		passphrase: CharArray,
	): UnlockResult {
		val picked = LockTarget(emulatedPath, documentUri)
		for ((io, resolved) in resolvedBackends(picked)) {
			val size = io.size(resolved) ?: continue
			val footer = io.readTail(resolved, LockTrailerCodec.FOOTER_SIZE) ?: continue
			val trailerLength = LockTrailerCodec.totalLength(footer) ?: continue
			if (size < trailerLength) continue
			val trailerBytes = io.readTail(resolved, trailerLength) ?: continue
			val trailer = LockTrailerCodec.decode(trailerBytes) ?: continue
			if (size != trailer.originalSize + trailerLength) continue

			val entry = LockedFile(
				path = resolved.path,
				displayName = displayName,
				sliceLen = trailer.cipherText.size,
				salt = trailer.salt.encode(),
				nonce = trailer.nonce.encode(),
				tag = trailer.tag.encode(),
				lockedAt = 0,
				documentUri = documentUri?.toString().orEmpty(),
				originalSize = trailer.originalSize,
				trailerLength = trailerLength,
			)
			val plain = open(entry, trailer.cipherText, passphrase)
				?: return UnlockResult.WrongPassphrase
			if (!io.writeHead(resolved, plain)) return UnlockResult.Failed("could not restore the header")
			if (!io.truncate(resolved, trailer.originalSize)) {
				return UnlockResult.Failed("header restored, but recovery data could not be removed")
			}
			store.remove(resolved.path)
			recovery.clear(resolved.path)
			return UnlockResult.Ok(restoreToIndex(io, resolved.path))
		}
		return UnlockResult.Failed("this file does not contain Shelf recovery data")
	}

	/** Reachable forms of [target], with a persisted document preferred over a root path. */
	private suspend fun resolvedBackends(target: LockTarget): List<Pair<HeaderIo, LockTarget>> {
		val resolved = mutableListOf<Pair<HeaderIo, LockTarget>>()
		for (io in backends) {
			if (!io.canAddress(target) || !io.isAvailable()) continue
			val path = io.resolve(target) ?: continue
			resolved += io to target.copy(path = path)
		}
		return resolved
	}

	private suspend fun readTrailer(
		io: HeaderIo,
		target: LockTarget,
		entry: LockedFile,
	): LockTrailer? {
		if (entry.trailerLength <= 0 || entry.originalSize < 0) return null
		val bytes = io.readTail(target, entry.trailerLength) ?: return null
		return LockTrailerCodec.decode(bytes)?.takeIf {
			it.originalSize == entry.originalSize && it.cipherText.size == entry.sliceLen
		}
	}

	/**
	 * Takes the file out of the gallery. Under root the rows are deleted outright; otherwise the best
	 * available is a rescan, which drops the row when MediaStore can no longer make sense of what it
	 * finds at that path — which, with an encrypted header, it cannot.
	 */
	private suspend fun forget(io: HeaderIo, path: String): String? = if (io is RootHeaderIo) {
		MediaStorePurge.forFile(appContext, paths.toEmulated(path))
	} else {
		MediaStorePurge.scan(appContext, listOf(path))
		null
	}

	private suspend fun restoreToIndex(io: HeaderIo, path: String): String? = if (io is RootHeaderIo) {
		MediaStorePurge.rescan(appContext, paths.toEmulated(path))
	} else {
		MediaStorePurge.scan(appContext, listOf(path))
		null
	}

	private fun open(entry: LockedFile, cipherText: ByteArray, passphrase: CharArray): ByteArray? =
		try {
			cipher.open(cipherText, entry.salt.decode(), entry.nonce.decode(), entry.tag.decode(), passphrase)
		} catch (e: javax.crypto.AEADBadTagException) {
			null
		} catch (e: javax.crypto.IllegalBlockSizeException) {
			null
		}

	private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)
	private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)

	private companion object {
		const val DEFAULT_SLICE = 1 shl 20 // 1 MiB
	}
}
