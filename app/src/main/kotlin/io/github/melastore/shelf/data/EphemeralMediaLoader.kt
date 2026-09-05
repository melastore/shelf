package io.github.melastore.shelf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.crypto.PasswordEnvelope
import io.github.melastore.shelf.root.StoragePaths
import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.SecretKey

enum class EphemeralMediaType { IMAGE, VIDEO }

data class EphemeralMediaItem(
	val name: String,
	val target: LockTarget,
	val isLocked: Boolean,
	val type: EphemeralMediaType = EphemeralMediaType.IMAGE,
)

object EphemeralMediaLoader {

	private val thumbnailCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
		override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
	}

	fun clearCache() {
		thumbnailCache.evictAll()
	}

	private const val SHELF_PREFIX = ".shelf-"
	private const val MAX_ATOMS = 4

	private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts", "flv")
	private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
	private val ISO_ATOMS = setOf("ftyp", "moov", "mdat")

	fun scanMediaItems(
		path: String,
		context: Context,
		paths: StoragePaths,
		keyFor: (ByteArray) -> SecretKey?,
		treeUri: String? = null,
	): List<EphemeralMediaItem> {
		val targets = targetsUnder(path, context, paths, treeUri)
		val nameMap = targets.firstOrNull { it.name == FolderNameProtector.MANIFEST }?.let(::readNameMap)

		val items = mutableListOf<EphemeralMediaItem>()
		for (target in targets) {
			if (target.size() <= 0) continue
			// Shelf's own bookkeeping files all carry the prefix, and .nomedia is a marker, not media.
			if (target.name.startsWith(SHELF_PREFIX) || target.name == ".nomedia") continue
			val realName = nameMap?.get(target.name) ?: target.name
			val trailer = FileLocker.readTrailer(target)
			val isLocked = trailer != null
			val sample = if (isLocked) {
				val key = keyFor(trailer.salt)
				if (key == null) {
					null
				} else {
					runCatching {
						HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
					}.getOrNull()
				}
			} else {
				runCatching {
					target.read(0, minOf(FileLocker.SLICE_LENGTH.toLong(), target.size()).toInt())
				}.getOrNull()
			}
			if (sample == null || sample.isEmpty()) continue

			if (isVideo(realName, sample)) {
				items += EphemeralMediaItem(realName, target, isLocked, EphemeralMediaType.VIDEO)
				continue
			}

			if (isImage(realName, sample)) {
				items += EphemeralMediaItem(realName, target, isLocked, EphemeralMediaType.IMAGE)
			}
		}
		return items
	}

	/**
	 * The original names of the files in a protected folder, or null if the manifest cannot be read.
	 * A folder is only ever browsed with the credential already in hand, so a failure here means a
	 * manifest from another credential and the opaque names are shown as they are.
	 */
	private fun readNameMap(manifest: LockTarget): Map<String, String>? {
		val size = manifest.size()
		if (size !in 1..PasswordEnvelope.MAX_ENVELOPE_BYTES.toLong()) return null
		val bytes = runCatching { manifest.read(0, size.toInt()) }.getOrNull() ?: return null
		val credential = ContentCredential.copy() ?: return null
		return try {
			FolderNameProtector.decryptManifest(bytes, credential)
		} finally {
			credential.fill(' ')
		}
	}

	private fun isVideo(name: String, sample: ByteArray): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase()
		if (ext in VIDEO_EXTENSIONS) return true
		if (ext in IMAGE_EXTENSIONS) return false
		if (sample.size < 4) return false

		if (sample[0] == 0x1A.toByte() && sample[1] == 0x45.toByte() &&
			sample[2] == 0xDF.toByte() && sample[3] == 0xA3.toByte()
		) {
			return true
		}

		if (sample.size >= 12 &&
			sample[0] == 'R'.code.toByte() && sample[1] == 'I'.code.toByte() &&
			sample[2] == 'F'.code.toByte() && sample[3] == 'F'.code.toByte() &&
			sample[8] == 'A'.code.toByte() && sample[9] == 'V'.code.toByte() &&
			sample[10] == 'I'.code.toByte()
		) {
			return true
		}

		if (sample.size >= 189 && sample[0] == 0x47.toByte() && sample[188] == 0x47.toByte()) {
			return true
		}

		if (sample[0] == 0.toByte() && sample[1] == 0.toByte() &&
			sample[2] == 1.toByte() && sample[3] == 0xBA.toByte()
		) {
			return true
		}

		if (sample.size >= 3 && sample[0] == 'F'.code.toByte() && sample[1] == 'L'.code.toByte() &&
			sample[2] == 'V'.code.toByte()
		) {
			return true
		}

		return hasIsoAtom(sample)
	}

	/**
	 * Walks the head of an ISO base media file, which is a chain of four-byte length, four-byte name.
	 * ftyp is usually first but a leading wide or free atom is legal, so a few links are followed
	 * before giving up. Searching the sample for the name instead would call any file that happens to
	 * contain "ftyp" a video.
	 */
	private fun hasIsoAtom(sample: ByteArray): Boolean {
		var offset = 0
		repeat(MAX_ATOMS) {
			if (offset < 0 || offset + 8 > sample.size) return false
			if (String(sample, offset + 4, 4, Charsets.US_ASCII) in ISO_ATOMS) return true
			val length = ByteBuffer.wrap(sample, offset, 4).int
			if (length < 8) return false
			offset += length
		}
		return false
	}

	private fun isImage(name: String, sample: ByteArray): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase()
		if (ext in IMAGE_EXTENSIONS) return true
		if (ext in VIDEO_EXTENSIONS) return false
		if (sample.size >= 3 && sample[0] == 0xFF.toByte() && sample[1] == 0xD8.toByte() && sample[2] == 0xFF.toByte()) {
			return true
		}
		if (sample.size >= 8 && sample[0] == 0x89.toByte() && sample[1] == 0x50.toByte() &&
			sample[2] == 0x4E.toByte() && sample[3] == 0x47.toByte()
		) {
			return true
		}
		if (sample.size >= 6 && sample[0] == 'G'.code.toByte() && sample[1] == 'I'.code.toByte() &&
			sample[2] == 'F'.code.toByte() && sample[3] == '8'.code.toByte()
		) {
			return true
		}
		if (sample.size >= 12 && sample[0] == 'R'.code.toByte() && sample[1] == 'I'.code.toByte() &&
			sample[2] == 'F'.code.toByte() && sample[3] == 'F'.code.toByte() &&
			sample[8] == 'W'.code.toByte() && sample[9] == 'E'.code.toByte() &&
			sample[10] == 'B'.code.toByte() && sample[11] == 'P'.code.toByte()
		) {
			return true
		}
		if (sample.size >= 2 && sample[0] == 'B'.code.toByte() && sample[1] == 'M'.code.toByte()) {
			return true
		}
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeByteArray(sample, 0, sample.size, bounds)
		return bounds.outWidth > 0 && bounds.outHeight > 0
	}

	/**
	 * Identifies one file within a scan. The walk descends into subfolders, so names repeat and a
	 * name is not an identity: two copies of the same photo one folder apart share both name and
	 * size.
	 */
	fun targetId(target: LockTarget): String = when (target) {
		is FileLockTarget -> target.file.path
		is SafLockTarget -> target.uri.toString()
		else -> target.name
	}

	fun loadThumbnail(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 300): Bitmap? {
		val cacheKey = "${targetId(item.target)}:${item.target.size()}:${item.type}:$maxDimension"
		thumbnailCache.get(cacheKey)?.let { return it }
		val bitmap = when (item.type) {
			EphemeralMediaType.IMAGE -> loadBitmap(item.target, keyFor, maxDimension)
			EphemeralMediaType.VIDEO -> loadVideoThumbnail(item.target, keyFor, maxDimension)
		}
		if (bitmap != null) {
			thumbnailCache.put(cacheKey, bitmap)
		}
		return bitmap
	}

	fun loadVideoThumbnail(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 300): Bitmap? {
		val trailer = FileLocker.readTrailer(target)
		val (slice, totalSize) = if (trailer != null) {
			val key = keyFor(trailer.salt) ?: return null
			val decryptedSlice = runCatching {
				HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
			}.getOrNull() ?: return null
			decryptedSlice to trailer.originalSize
		} else {
			byteArrayOf() to target.size()
		}

		val dataSource = EphemeralMediaDataSource(target, slice, totalSize)
		val retriever = MediaMetadataRetriever()
		return try {
			retriever.setDataSource(dataSource)
			val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
				?: retriever.frameAtTime
			if (frame != null && maxDimension > 0 && (frame.width > maxDimension || frame.height > maxDimension)) {
				val scale = minOf(maxDimension.toFloat() / frame.width, maxDimension.toFloat() / frame.height)
				val scaled = Bitmap.createScaledBitmap(
					frame,
					(frame.width * scale).toInt().coerceAtLeast(1),
					(frame.height * scale).toInt().coerceAtLeast(1),
					true,
				)
				if (scaled != frame) frame.recycle()
				scaled
			} else {
				frame
			}
		} catch (_: Exception) {
			null
		} finally {
			try {
				retriever.release()
			} catch (_: Exception) {}
			dataSource.close()
		}
	}

	fun loadBitmap(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 0): Bitmap? {
		val trailer = FileLocker.readTrailer(target)
		val (slice, totalSize) = if (trailer != null) {
			val key = keyFor(trailer.salt) ?: return null
			val decryptedSlice = runCatching {
				HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
			}.getOrNull() ?: return null
			decryptedSlice to trailer.originalSize
		} else {
			byteArrayOf() to target.size()
		}

		fun newStream() = BufferedInputStream(EphemeralDecryptedStream(target, slice, totalSize), 64 * 1024)

		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		newStream().use { s -> BitmapFactory.decodeStream(s, null, bounds) }
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

		val sampleSize = if (maxDimension > 0) {
			calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension, maxDimension)
		} else {
			1
		}

		val decodeOptions = BitmapFactory.Options().apply {
			inSampleSize = sampleSize
			inPreferredConfig = Bitmap.Config.ARGB_8888
		}
		val decoded = newStream().use { s -> BitmapFactory.decodeStream(s, null, decodeOptions) } ?: return null

		return if (maxDimension > 0 && (decoded.width > maxDimension || decoded.height > maxDimension)) {
			val scale = minOf(maxDimension.toFloat() / decoded.width, maxDimension.toFloat() / decoded.height)
			val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
			val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
			val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
			if (scaled != decoded) decoded.recycle()
			scaled
		} else {
			decoded
		}
	}

	private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
		var inSampleSize = 1
		if (height > reqHeight || width > reqWidth) {
			val halfHeight = height / 2
			val halfWidth = width / 2
			while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
				inSampleSize *= 2
			}
		}
		return inSampleSize
	}

	private fun targetsUnder(
		path: String,
		context: Context,
		paths: StoragePaths,
		treeUri: String? = null,
	): List<LockTarget> {
		val direct = File(path).takeIf(::canWalkAsFile)?.let(ContentLocker::targetsUnder)
		if (!direct.isNullOrEmpty()) return direct

		if (!treeUri.isNullOrBlank()) {
			val tree = runCatching { Uri.parse(treeUri) }.getOrNull()
			val id = SafPaths.documentId(paths.emulatedRoot, path)
			if (tree != null && id != null) {
				val docUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
				val folder = docUri?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.isDirectory }
				val targets = folder?.let { SafLockTarget.targetsUnder(context.contentResolver, it) }
				if (!targets.isNullOrEmpty()) return targets
			}
		}

		val saf = SafGrants.folder(context.applicationContext, paths, path)
			?.let { SafLockTarget.targetsUnder(context.contentResolver, it) }
		return saf.orEmpty()
	}
}
