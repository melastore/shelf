package io.github.melastore.shelf.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A journal written by an earlier version has to keep parsing. If it did not, every folder recorded
 * in it would sit at mode 000 with nothing left to say what its permissions used to be.
 */
class HiddenEntryTest {

	private val json = Json { ignoreUnknownKeys = true }

	@Test fun readsAJournalWrittenBeforeThereWereMethods() {
		val legacy = """
			[{
				"backingPath": "/data/media/0/DCIM/Old",
				"displayName": "Old",
				"originalMode": "755",
				"originalOwner": "1023:1023",
				"hiddenAt": 1700000000000
			}]
		""".trimIndent()

		val entries = json.decodeFromString(ListSerializer(HiddenEntry.serializer()), legacy)

		assertEquals(1, entries.size)
		assertEquals(HideMethod.ROOT_CHMOD, entries.first().method)
		assertEquals("/data/media/0/DCIM/Old", entries.first().path)
		assertEquals("755", entries.first().originalMode)
	}

	@Test fun keepsWritingTheOldKeySoOlderBuildsCanStillRead() {
		val entry = HiddenEntry(
			path = "/storage/emulated/0/DCIM/Holiday",
			displayName = "Holiday",
			hiddenAt = 1L,
			method = HideMethod.DOT_RENAME,
			hiddenPath = "/storage/emulated/0/DCIM/.Holiday",
		)
		val encoded = json.encodeToString(HiddenEntry.serializer(), entry)

		assertTrue(encoded.contains("\"backingPath\""))
		assertEquals(entry, json.decodeFromString(HiddenEntry.serializer(), encoded))
	}
}
