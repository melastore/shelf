package io.github.melastore.shelf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.root.StoragePaths
import java.io.BufferedInputStream
import java.io.File
import javax.crypto.SecretKey

enum class EphemeralMediaType { IMAGE, VIDEO }

data class EphemeralMediaItem(
	val name: String,
	val target: LockTarget,
	val isLocked: Boolean,
	val type: EphemeralMediaType = EphemeralMediaType.IMAGE,
)

object EphemeralMediaLoader {

	private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts")
	private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

	fun scanMediaItems(
		path: String,
		context: Context,
		paths: StoragePaths,
		keyFor: (ByteArray) -> SecretKey?,
	): List<EphemeralMediaItem> {
		val targets = targetsUnder(path, context, paths)
		val items = mutableListOf<EphemeralMediaItem>()
		for (target in targets) {
			if (target.size() <= 0) continue
			val trailer = FileLocker.readTrailer(target)
			val isLocked = trailer != null
			val sample = if (isLocked) {
				val key = keyFor(trailer.salt)
				if (key == null) {
					null
				} else {
					runCatching {
						val slice = HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
						slice.copyOfRange(0, minOf(slice.size, 8192))
					}.getOrNull()
				}
			} else {
				runCatching {
					target.read(0, minOf(8192, target.size().toInt()))
				}.getOrNull()
			}
			if (sample == null || sample.isEmpty()) continue

			if (isVideo(target.name, sample)) {
				val name = target.name.takeIf { it.isNotEmpty() } ?: "Video ${items.size + 1}"
				items += EphemeralMediaItem(name, target, isLocked, EphemeralMediaType.VIDEO)
				continue
			}

			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeByteArray(sample, 0, sample.size, bounds)
			if (bounds.outWidth > 0 && bounds.outHeight > 0) {
				val name = target.name.takeIf { it.isNotEmpty() } ?: "Image ${items.size + 1}"
				items += EphemeralMediaItem(name, target, isLocked, EphemeralMediaType.IMAGE)
			}
		}
		return items
	}

	private fun isVideo(name: String, sample: ByteArray): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase()
		if (ext in VIDEO_EXTENSIONS) return true
		if (ext in IMAGE_EXTENSIONS) return false
		if (sample.size >= 8) {
			if (sample[4] == 'f'.code.toByte() && sample[5] == 't'.code.toByte() &&
				sample[6] == 'y'.code.toByte() && sample[7] == 'p'.code.toByte()
			) {
				return true
			}
			if (sample[0] == 0x1A.toByte() && sample[1] == 0x45.toByte() &&
				sample[2] == 0xDF.toByte() && sample[3] == 0xA3.toByte()
			) {
				return true
			}
			if (sample[0] == 'R'.code.toByte() && sample[1] == 'I'.code.toByte() &&
				sample[2] == 'F'.code.toByte() && sample[3] == 'F'.code.toByte() &&
				sample.size >= 12 && sample[8] == 'A'.code.toByte() &&
				sample[9] == 'V'.code.toByte() && sample[10] == 'I'.code.toByte()
			) {
				return true
			}
		}
		return false
	}

	fun loadThumbnail(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 300,): Bitmap? =
		when (item.type) {
			EphemeralMediaType.IMAGE -> loadBitmap(item.target, keyFor, maxDimension)
			EphemeralMediaType.VIDEO -> loadVideoThumbnail(item.target, keyFor, maxDimension)
		}

	fun loadVideoThumbnail(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 300,): Bitmap? {
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
		}
	}

	fun loadBitmap(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 0,): Bitmap? {
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

		fun newStream() = BufferedInputStream(EphemeralDecryptedStream(target, slice, totalSize))

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
		return newStream().use { s -> BitmapFactory.decodeStream(s, null, decodeOptions) }
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

	private fun targetsUnder(path: String, context: Context, paths: StoragePaths): List<LockTarget> {
		val direct = File(path).takeIf(::canWalkAsFile)?.let(ContentLocker::targetsUnder)
		if (!direct.isNullOrEmpty()) return direct

		val saf = SafGrants.folder(context.applicationContext, paths, path)
			?.let { SafLockTarget.targetsUnder(context.contentResolver, it) }
		if (!saf.isNullOrEmpty()) return saf

		return saf ?: direct ?: emptyList()
	}
}
