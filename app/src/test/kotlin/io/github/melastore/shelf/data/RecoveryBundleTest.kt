package io.github.melastore.shelf.data

import io.github.melastore.shelf.root.StoragePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecoveryBundleTest {

	private val codec = RecoveryBundleCodec(StoragePaths(0))

	@Test
	fun `round trip removes SAF grants and keeps portable paths`() {
		val entries = listOf(
			HiddenEntry(
				path = "/storage/emulated/0/DCIM/Holiday",
				displayName = "Holiday",
				hiddenAt = 10,
				method = HideMethod.DOT_RENAME,
				hiddenPath = "/storage/emulated/0/DCIM/.Holiday",
				treeUri = "content://grant-that-will-not-survive-reinstall",
			),
			HiddenEntry(
				path = "/data/media/0/Pictures/Private",
				displayName = "Private",
				hiddenAt = 20,
				method = HideMethod.ROOT_CHMOD,
				originalMode = "770",
				originalOwner = "1023:1023",
			),
		)

		val restored = codec.decode(codec.encode(entries, now = 30))

		assertEquals(entries.map { it.copy(treeUri = "") }, restored)
	}

	@Test
	fun `traversal and private vault escapes are rejected`() {
		val valid = HiddenEntry(
			path = "/storage/emulated/0/DCIM/Test",
			displayName = "Test",
			hiddenAt = 1,
			method = HideMethod.PRIVATE_MOVE,
			hiddenPath = "/storage/emulated/0/.shelf/id/.payload-id",
		)
		val json = codec.encode(listOf(valid)).toString(Charsets.UTF_8)

		assertThrows(IllegalArgumentException::class.java) {
			codec.decode(json.replace("DCIM/Test", "../Test").toByteArray())
		}
		assertThrows(IllegalArgumentException::class.java) {
			codec.decode(json.replace(".shelf/id/.payload-id", "Download/not-a-vault").toByteArray())
		}
	}
}
