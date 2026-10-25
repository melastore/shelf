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
import java.util.concurrent.Semaphore
import javax.crypto.SecretKey

enum class EphemeralMediaType { IMAGE, VIDEO, AUDIO, TEXT, PDF }

data class EphemeralMediaItem(
	val name: String,
	val target: LockTarget,
	val isLocked: Boolean,
	val type: EphemeralMediaType = EphemeralMediaType.IMAGE,
)

/** A row's picture and the duration shown beside it. A null [bitmap] means nothing would decode. */
data class EphemeralThumbnail(val bitmap: Bitmap?, val durationMs: Long = 0L)

class EphemeralText(val text: String, val truncated: Boolean)

object EphemeralMediaLoader {

	private val thumbnailCache = object : LruCache<String, EphemeralThumbnail>(24 * 1024 * 1024) {
		override fun sizeOf(key: String, value: EphemeralThumbnail): Int = value.bitmap?.byteCount ?: 1
	}

	fun clearCache() {
		thumbnailCache.evictAll()
	}

	private const val MAX_ATOMS = 4

	private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts", "flv")
	private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "wav", "flac", "aac", "opus", "wma", "mid")
	private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
	private val ISO_ATOMS = setOf("ftyp", "moov", "mdat")
	private val TEXT_EXTENSIONS = setOf(
		"txt", "md", "log", "csv", "tsv", "json", "xml", "html", "htm", "css", "srt", "vtt", "ass",
		"ini", "cfg", "conf", "yml", "yaml", "toml", "properties", "sql", "sh", "bat", "kt", "java",
		"py", "js", "ts", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "lua", "m3u", "m3u8",
	)

	/** A megabyte of text is more than anyone reads on a phone, and all of it sits in RAM. */
	const val MAX_TEXT_BYTES = 1024 * 1024

	/** A PDF is held whole in memory to render, so the ceiling is memory, not patience. */
	const val MAX_PDF_BYTES = 64 * 1024 * 1024

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
			// sizeOrZero, not size: a stale grant throws here, and one such file used to end the scan.
			val size = target.sizeOrZero()
			if (size <= 0) continue
			// The walk already drops Shelf's own files; .nomedia is a marker, not media.
			if (isShelfBookkeeping(target.name) || target.name == ".nomedia") continue
			val realName = nameMap?.get(target.name) ?: target.name
			val trailer = FileLocker.readTrailer(target)
			val isLocked = trailer != null
			// A known extension settles the type with no read at all, which on a document provider is a
			// Binder round trip and a 64 KB slice per file before the list could show. A file whose
			// head does not decrypt fails when it is opened instead.
			val byName = typeByExtension(realName)
			if (byName != null && (trailer == null || keyFor(trailer.salt) != null)) {
				items += EphemeralMediaItem(realName, target, isLocked, byName)
				continue
			}
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
					target.read(0, minOf(FileLocker.SLICE_LENGTH.toLong(), size).toInt())
				}.getOrNull()
			}
			if (sample == null || sample.isEmpty()) continue

			// Extension and magic settle a file outright. The bitmap decoder is a guess and says yes
			// far too readily, so it only sees what nothing else has claimed.
			val type = when {
				isPdf(realName, sample) -> EphemeralMediaType.PDF
				isVideo(realName, sample) -> EphemeralMediaType.VIDEO
				isAudio(realName, sample) -> EphemeralMediaType.AUDIO
				isImageByHeader(realName, sample) -> EphemeralMediaType.IMAGE
				isText(realName, sample) -> EphemeralMediaType.TEXT
				decodesAsBitmap(sample) -> EphemeralMediaType.IMAGE
				else -> null
			}
			if (type != null) {
				items += EphemeralMediaItem(realName, target, isLocked, type)
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
		val size = manifest.sizeOrZero()
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

	/** The same order as the sample checks, so `ts` is still a video before it is source code. */
	private fun typeByExtension(name: String): EphemeralMediaType? = when (name.substringAfterLast('.', "").lowercase()) {
		"" -> null
		"pdf" -> EphemeralMediaType.PDF
		in VIDEO_EXTENSIONS -> EphemeralMediaType.VIDEO
		in AUDIO_EXTENSIONS -> EphemeralMediaType.AUDIO
		in IMAGE_EXTENSIONS -> EphemeralMediaType.IMAGE
		in TEXT_EXTENSIONS -> EphemeralMediaType.TEXT
		else -> null
	}

	private fun isPdf(name: String, sample: ByteArray): Boolean {
		if (name.substringAfterLast('.', "").lowercase() == "pdf") return true
		return sample.size >= 5 && sample[0] == '%'.code.toByte() && sample[1] == 'P'.code.toByte() &&
			sample[2] == 'D'.code.toByte() && sample[3] == 'F'.code.toByte() && sample[4] == '-'.code.toByte()
	}

	private fun isAudio(name: String, sample: ByteArray): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase()
		if (ext in AUDIO_EXTENSIONS) return true
		if (ext in IMAGE_EXTENSIONS || ext in VIDEO_EXTENSIONS) return false
		if (sample.size < 4) return false

		// ID3v2 tag (MP3)
		if (sample[0] == 'I'.code.toByte() && sample[1] == 'D'.code.toByte() && sample[2] == '3'.code.toByte()) {
			return true
		}
		// OggS (OGG / OPUS)
		if (sample[0] == 'O'.code.toByte() && sample[1] == 'g'.code.toByte() &&
			sample[2] == 'g'.code.toByte() && sample[3] == 'S'.code.toByte()
		) {
			return true
		}
		// fLaC (FLAC)
		if (sample[0] == 'f'.code.toByte() && sample[1] == 'L'.code.toByte() &&
			sample[2] == 'a'.code.toByte() && sample[3] == 'C'.code.toByte()
		) {
			return true
		}
		// RIFF ... WAVE (WAV)
		if (sample.size >= 12 &&
			sample[0] == 'R'.code.toByte() && sample[1] == 'I'.code.toByte() &&
			sample[2] == 'F'.code.toByte() && sample[3] == 'F'.code.toByte() &&
			sample[8] == 'W'.code.toByte() && sample[9] == 'A'.code.toByte() &&
			sample[10] == 'V'.code.toByte() && sample[11] == 'E'.code.toByte()
		) {
			return true
		}
		// MP3 frame sync (11 bits set: 0xFF 0xE0..0xFF)
		if (sample[0] == 0xFF.toByte() && (sample[1].toInt() and 0xE0) == 0xE0) {
			return true
		}
		return false
	}

	/**
	 * Text by extension, or by the bytes when there is no extension. An extension naming some other
	 * kind is taken at its word: sniffing would offer a font as a document.
	 */
	private fun isText(name: String, sample: ByteArray): Boolean {
		val ext = name.substringAfterLast('.', "").lowercase()
		if (ext in TEXT_EXTENSIONS) return true
		if (ext.isNotEmpty()) return false
		return looksLikeText(sample)
	}

	private fun looksLikeText(sample: ByteArray): Boolean {
		if (sample.isEmpty()) return false
		val examined = minOf(sample.size, 4_096)
		var printable = 0
		for (i in 0 until examined) {
			val b = sample[i].toInt() and 0xFF
			// A NUL is the one byte no text file of any encoding carries in its body.
			if (b == 0) return false
			if (b >= 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) printable++
		}
		return printable * 100 / examined >= 95
	}

	private fun isImageByHeader(name: String, sample: ByteArray): Boolean {
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
		return sample.size >= 2 && sample[0] == 'B'.code.toByte() && sample[1] == 'M'.code.toByte()
	}

	/** The last resort: a format with no header this knows, offered to the platform decoder. */
	private fun decodesAsBitmap(sample: ByteArray): Boolean {
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

	fun loadThumbnail(
		item: EphemeralMediaItem,
		keyFor: (ByteArray) -> SecretKey?,
		maxDimension: Int = 300,
	): EphemeralThumbnail {
		val cacheKey = "${targetId(item.target)}:${item.target.sizeOrZero()}:${item.type}:$maxDimension"
		thumbnailCache.get(cacheKey)?.let { return it }
		val loaded = when (item.type) {
			EphemeralMediaType.IMAGE -> EphemeralThumbnail(loadBitmap(item.target, keyFor, maxDimension))
			EphemeralMediaType.VIDEO -> loadVideoThumbnail(item.target, keyFor, maxDimension)
			EphemeralMediaType.AUDIO -> loadAudioThumbnail(item.target, keyFor, maxDimension)
			EphemeralMediaType.PDF -> EphemeralThumbnail(loadPdfThumbnail(item.target, keyFor, maxDimension))
			EphemeralMediaType.TEXT -> EphemeralThumbnail(null)
		}
		// Misses are not cached: retrievers fail for reasons that pass, and a cached null is a row
		// that stays blank until the viewer closes.
		if (loaded.bitmap != null) {
			thumbnailCache.put(cacheKey, loaded)
		}
		return loaded
	}

	/**
	 * The decrypted head slice and the file's real length, or null when the credential in hand does
	 * not open it. An unlocked file has no slice and answers with its own size.
	 */
	fun plaintextHead(target: LockTarget, keyFor: (ByteArray) -> SecretKey?): Pair<ByteArray, Long>? {
		val trailer = FileLocker.readTrailer(target) ?: return byteArrayOf() to target.sizeOrZero()
		val key = keyFor(trailer.salt) ?: return null
		val slice = runCatching {
			HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
		}.getOrNull() ?: return null
		return slice to trailer.originalSize
	}

	/** Plaintext bytes from the head of a file, never more than [maxBytes], without touching disk. */
	fun readPlaintext(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxBytes: Int): ByteArray? {
		val (slice, totalSize) = plaintextHead(target, keyFor) ?: return null
		if (totalSize < 0) return null
		if (totalSize == 0L) return byteArrayOf()
		val limit = minOf(totalSize, maxBytes.toLong()).toInt()
		val out = ByteArray(limit)
		var filled = 0
		return runCatching {
			BufferedInputStream(EphemeralDecryptedStream(target, slice, totalSize), 64 * 1024).use { stream ->
				while (filled < limit) {
					val read = stream.read(out, filled, limit - filled)
					if (read <= 0) break
					filled += read
				}
			}
			if (filled == limit) out else out.copyOf(filled)
		}.getOrNull()
	}

	fun loadVideoThumbnail(
		target: LockTarget,
		keyFor: (ByteArray) -> SecretKey?,
		maxDimension: Int = 300,
	): EphemeralThumbnail {
		val (slice, totalSize) = plaintextHead(target, keyFor) ?: return EphemeralThumbnail(null)
		if (totalSize <= 0) return EphemeralThumbnail(null)

		val dataSource = EphemeralMediaDataSource(target, slice, totalSize)
		val retriever = MediaMetadataRetriever()
		return try {
			retriever.setDataSource(dataSource)
			val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
				?.toLongOrNull()
				?.coerceAtLeast(0L)
				?: 0L
			EphemeralThumbnail(scaled(frameFrom(retriever, durationMs), maxDimension), durationMs)
		} catch (_: Exception) {
			EphemeralThumbnail(null)
		} finally {
			try {
				retriever.release()
			} catch (_: Exception) {}
			dataSource.close()
		}
	}

	fun loadAudioThumbnail(
		target: LockTarget,
		keyFor: (ByteArray) -> SecretKey?,
		maxDimension: Int = 300,
	): EphemeralThumbnail {
		val (slice, totalSize) = plaintextHead(target, keyFor) ?: return EphemeralThumbnail(null)
		if (totalSize <= 0) return EphemeralThumbnail(null)

		val dataSource = EphemeralMediaDataSource(target, slice, totalSize)
		val retriever = MediaMetadataRetriever()
		return try {
			retriever.setDataSource(dataSource)
			val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
				?.toLongOrNull()
				?.coerceAtLeast(0L)
				?: 0L
			val artBytes = retriever.embeddedPicture
			val artBitmap = artBytes?.let {
				BitmapFactory.decodeByteArray(it, 0, it.size)?.let { bmp -> scaled(bmp, maxDimension) }
			}
			EphemeralThumbnail(artBitmap, durationMs)
		} catch (_: Exception) {
			EphemeralThumbnail(null)
		} finally {
			try {
				retriever.release()
			} catch (_: Exception) {}
			dataSource.close()
		}
	}

	/**
	 * A frame worth showing, most to least likely. A tenth in first, since plenty of video opens on
	 * black and a black thumbnail looks like none. A non-sync frame last: it costs a decode from the
	 * previous keyframe, but for some files it is the only frame there is.
	 */
	private fun frameFrom(retriever: MediaMetadataRetriever, durationMs: Long): Bitmap? {
		val offsets = if (durationMs > 0) listOf(durationMs * 1000 / 10, 0L) else listOf(0L)
		for (offset in offsets) {
			runCatching {
				retriever.getFrameAtTime(offset, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
			}.getOrNull()?.let { return it }
		}
		return runCatching {
			retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) ?: retriever.frameAtTime
		}.getOrNull()
	}

	private fun scaled(frame: Bitmap?, maxDimension: Int): Bitmap? {
		if (frame == null || maxDimension <= 0) return frame
		if (frame.width <= maxDimension && frame.height <= maxDimension) return frame
		val scale = minOf(maxDimension.toFloat() / frame.width, maxDimension.toFloat() / frame.height)
		val out = Bitmap.createScaledBitmap(
			frame,
			(frame.width * scale).toInt().coerceAtLeast(1),
			(frame.height * scale).toInt().coerceAtLeast(1),
			true,
		)
		if (out != frame) frame.recycle()
		return out
	}

	fun loadBitmap(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int = 0): Bitmap? {
		val (slice, totalSize) = plaintextHead(target, keyFor) ?: return null

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

	/**
	 * The file as text, or null if it will not decode. Cut at [MAX_TEXT_BYTES], on a character
	 * boundary, and says so: guessing from the decoded length got both directions wrong.
	 */
	fun loadText(target: LockTarget, keyFor: (ByteArray) -> SecretKey?): EphemeralText? {
		val bytes = readPlaintext(target, keyFor, MAX_TEXT_BYTES + 1) ?: return null
		if (bytes.size <= MAX_TEXT_BYTES) return EphemeralText(bytes.toString(Charsets.UTF_8), truncated = false)
		// Back off any UTF-8 continuation bytes, so the cut never splits a character.
		var end = MAX_TEXT_BYTES
		while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
		return EphemeralText(String(bytes, 0, end, Charsets.UTF_8), truncated = true)
	}

	/**
	 * Streamed into memory outside the Java heap. One over [MAX_PDF_BYTES] is refused rather than
	 * cut short: a PDF keeps its index at the end, so a truncated one never opened anyway.
	 */
	fun loadPdf(target: LockTarget, keyFor: (ByteArray) -> SecretKey?): EphemeralPdf? {
		val (slice, totalSize) = plaintextHead(target, keyFor) ?: return null
		return try {
			if (totalSize <= 0 || totalSize > MAX_PDF_BYTES) return null
			runCatching {
				BufferedInputStream(EphemeralDecryptedStream(target, slice, totalSize), 64 * 1024).use(EphemeralPdf::open)
			}.getOrNull()
		} finally {
			slice.fill(0)
		}
	}

	// Two at a time: each holds a whole document in memory while its first page renders.
	private val pdfThumbnails = Semaphore(2)

	private fun loadPdfThumbnail(target: LockTarget, keyFor: (ByteArray) -> SecretKey?, maxDimension: Int,): Bitmap? {
		pdfThumbnails.acquire()
		return try {
			loadPdf(target, keyFor)?.use { it.renderPage(0, maxDimension) }
		} finally {
			pdfThumbnails.release()
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
