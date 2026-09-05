package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EphemeralMediaLoaderTest {

	@get:Rule val temp = TemporaryFolder()

	private val context: Application get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `scans media items and decodes manifest in hidden folder`() = runBlocking {
		val password = "testpassphrase".toCharArray()
		ContentCredential.set(password)
		try {
			val folder = temp.newFolder("vacation")
			val paths = StoragePaths.forTest(temp.root.path, temp.root.path)
			val keyCache = mutableMapOf<String, SecretKey>()
			val keyFor: (ByteArray) -> SecretKey? = { salt ->
				keyCache.getOrPut(salt.contentToString()) {
					HeaderCipher.deriveKey(password, salt)
				}
			}

			val mp4Bytes = byteArrayOf(
				0x00, 0x00, 0x00, 0x18,
				'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
				'm'.code.toByte(), 'p'.code.toByte(), '4'.code.toByte(), '2'.code.toByte()
			) + ByteArray(100)
			File(folder, "movie.mp4").writeBytes(mp4Bytes)

			val pngBytes = byteArrayOf(
				0x89.toByte(),
				0x50.toByte(),
				0x4E.toByte(),
				0x47.toByte(),
				0x0D.toByte(),
				0x0A.toByte(),
				0x1A.toByte(),
				0x0A.toByte()
			) + ByteArray(100)
			File(folder, "photo.png").writeBytes(pngBytes)

			val contentProtector = FolderContentProtector(context, paths)
			val nameProtector = FolderNameProtector(context, paths)

			contentProtector.protect(folder.path)
			nameProtector.protect(folder.path)

			val items = EphemeralMediaLoader.scanMediaItems(folder.path, context, paths, keyFor)
			assertEquals(2, items.size)

			val videoItem = items.firstOrNull { it.type == EphemeralMediaType.VIDEO }
			val imageItem = items.firstOrNull { it.type == EphemeralMediaType.IMAGE }

			assertTrue(videoItem != null)
			assertEquals("movie.mp4", videoItem?.name)
			assertTrue(videoItem?.isLocked == true)

			assertTrue(imageItem != null)
			assertEquals("photo.png", imageItem?.name)
			assertTrue(imageItem?.isLocked == true)
		} finally {
			ContentCredential.clear()
		}
	}

	@Test
	fun `detects video with atom header preceding ftyp without extension`() {
		val paths = StoragePaths.forTest(temp.root.path, temp.root.path)
		val folder = temp.newFolder("unnamed_video")
		val keyFor: (ByteArray) -> SecretKey? = { null }

		val wideAtom = byteArrayOf(
			0x00,
			0x00,
			0x00,
			0x08,
			'w'.code.toByte(),
			'i'.code.toByte(),
			'd'.code.toByte(),
			'e'.code.toByte()
		)
		val ftypAtom = byteArrayOf(
			0x00, 0x00, 0x00, 0x10,
			'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
			'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()
		)
		val file = File(folder, "sfn_0123456789abcdef0123456789abcdef")
		file.writeBytes(wideAtom + ftypAtom + ByteArray(200))

		val items = EphemeralMediaLoader.scanMediaItems(folder.path, context, paths, keyFor)
		assertEquals(1, items.size)
		assertEquals(EphemeralMediaType.VIDEO, items[0].type)
	}

	@Test
	fun `does not read an ftyp further into a file as a video`() {
		val paths = StoragePaths.forTest(temp.root.path, temp.root.path)
		val folder = temp.newFolder("not_video")
		val keyFor: (ByteArray) -> SecretKey? = { null }

		val payload = ByteArray(400) + "ftyp".toByteArray(Charsets.US_ASCII) + ByteArray(400)
		File(folder, "sfn_0123456789abcdef0123456789abcdef").writeBytes(payload)

		val items = EphemeralMediaLoader.scanMediaItems(folder.path, context, paths, keyFor)
		assertTrue(items.none { it.type == EphemeralMediaType.VIDEO })
	}

	@Test
	fun `tells apart two files sharing a name in different subfolders`() {
		val paths = StoragePaths.forTest(temp.root.path, temp.root.path)
		val folder = temp.newFolder("trip")
		val keyFor: (ByteArray) -> SecretKey? = { null }

		val png = byteArrayOf(
			0x89.toByte(),
			0x50.toByte(),
			0x4E.toByte(),
			0x47.toByte(),
			0x0D.toByte(),
			0x0A.toByte(),
			0x1A.toByte(),
			0x0A.toByte()
		) + ByteArray(100)
		File(folder, "day one").mkdirs()
		File(folder, "day two").mkdirs()
		File(folder, "day one/photo.png").writeBytes(png)
		File(folder, "day two/photo.png").writeBytes(png)

		val items = EphemeralMediaLoader.scanMediaItems(folder.path, context, paths, keyFor)
		assertEquals(2, items.size)
		// The grid keys rows by this, and a repeat would take the list down with it.
		assertEquals(2, items.map { EphemeralMediaLoader.targetId(it.target) }.toSet().size)
	}

	@Test
	fun `ignores manifest and shelf internal files`() {
		val paths = StoragePaths.forTest(temp.root.path, temp.root.path)
		val folder = temp.newFolder("empty_vault")
		val keyFor: (ByteArray) -> SecretKey? = { null }

		File(folder, ".shelf-names-v1").writeBytes(ByteArray(64))
		File(folder, ".shelf-origin-v1").writeBytes(ByteArray(32))
		File(folder, ".nomedia").createNewFile()

		val items = EphemeralMediaLoader.scanMediaItems(folder.path, context, paths, keyFor)
		assertTrue(items.isEmpty())
	}
}
