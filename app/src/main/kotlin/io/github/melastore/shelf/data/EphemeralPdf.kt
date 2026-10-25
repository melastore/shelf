package io.github.melastore.shelf.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.InputStream

/**
 * A PDF rendered from memory, with no plaintext copy on disk at any point.
 *
 * [PdfRenderer] takes a seekable descriptor and nothing else, which normally means writing the
 * decrypted file to a cache directory first. `memfd_create` gives an anonymous descriptor instead:
 * seekable, no name, no directory entry, freed when the last handle closes. API 30, our minimum.
 *
 * A renderer allows one open page at a time, so [renderPage] is serialised.
 */
class EphemeralPdf private constructor(
	private val descriptor: ParcelFileDescriptor,
	private val renderer: PdfRenderer,
) : AutoCloseable {

	private var closed = false

	val pageCount: Int get() = renderer.pageCount

	@Synchronized
	fun renderPage(index: Int, width: Int): Bitmap? {
		if (closed || index !in 0 until renderer.pageCount || width <= 0) return null
		return runCatching {
			renderer.openPage(index).use { page ->
				val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
				val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
				// A page renders ink only, so unpainted bitmap shows black where the paper should be.
				bitmap.eraseColor(Color.WHITE)
				page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
				bitmap
			}
		}.getOrNull()
	}

	@Synchronized
	override fun close() {
		if (closed) return
		closed = true
		runCatching { renderer.close() }
		runCatching { descriptor.close() }
	}

	companion object {
		fun open(bytes: ByteArray): EphemeralPdf? = if (bytes.isEmpty()) null else open(bytes.inputStream())

		/**
		 * Copies [source] into the anonymous descriptor through one small buffer. Read whole into a
		 * byte array first, a screen of large PDFs asked for one such array per thumbnail at once
		 * and ran the heap out.
		 */
		fun open(source: InputStream): EphemeralPdf? {
			val memory = runCatching { Os.memfd_create("shelf-pdf", 0) }.getOrNull() ?: return null
			val buffer = ByteArray(COPY_BUFFER)
			val descriptor = try {
				var total = 0L
				while (true) {
					val read = source.read(buffer)
					if (read < 0) break
					var written = 0
					while (written < read) {
						val count = Os.write(memory, buffer, written, read - written)
						if (count <= 0) return null
						written += count
					}
					total += read
				}
				if (total == 0L) return null
				Os.lseek(memory, 0, OsConstants.SEEK_SET)
				ParcelFileDescriptor.dup(memory)
			} catch (_: Exception) {
				return null
			} finally {
				buffer.fill(0)
				runCatching { Os.close(memory) }
			}
			return runCatching { EphemeralPdf(descriptor, PdfRenderer(descriptor)) }.getOrElse {
				runCatching { descriptor.close() }
				null
			}
		}

		private const val COPY_BUFFER = 64 * 1024
	}
}
