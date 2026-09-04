package io.github.melastore.shelf.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.root.StoragePaths
import java.io.BufferedInputStream
import java.io.File
import javax.crypto.SecretKey

data class EphemeralMediaItem(val name: String, val target: LockTarget, val isLocked: Boolean,)

object EphemeralMediaLoader {

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
			val isImg = if (isLocked) {
				val key = keyFor(trailer.salt)
				if (key == null) {
					false
				} else {
					runCatching {
						val slice = HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
						val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
						BitmapFactory.decodeByteArray(slice, 0, minOf(slice.size, 8192), bounds)
						bounds.outWidth > 0 && bounds.outHeight > 0
					}.getOrDefault(false)
				}
			} else {
				runCatching {
					val head = target.read(0, minOf(8192, target.size().toInt()))
					val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
					BitmapFactory.decodeByteArray(head, 0, head.size, bounds)
					bounds.outWidth > 0 && bounds.outHeight > 0
				}.getOrDefault(false)
			}
			if (isImg) {
				val name = target.name.takeIf { it.isNotEmpty() } ?: "Image ${items.size + 1}"
				items += EphemeralMediaItem(name, target, isLocked)
			}
		}
		return items
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
