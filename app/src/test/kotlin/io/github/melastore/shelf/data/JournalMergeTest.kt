package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JournalMergeTest {

	@get:Rule val temp = TemporaryFolder()

	private fun entry(path: String, name: String = path.substringAfterLast('/'), at: Long = 1) = HiddenEntry(
		path = path,
		displayName = name,
		hiddenAt = at,
		method = HideMethod.DOT_RENAME,
		hiddenPath = "${path.substringBeforeLast('/')}/.$name",
	)

	@Test
	fun `a record that disagrees is skipped without losing the rest of the import`() = runBlocking {
		val journal = Journal(File(temp.root, "journal.json"))
		journal.addNew(entry("/storage/emulated/0/Held", at = 1))

		val result = journal.merge(
			listOf(
				entry("/storage/emulated/0/Held", at = 999),
				entry("/storage/emulated/0/Fresh"),
			),
		)

		assertEquals(1, result.added)
		assertEquals(0, result.duplicates)
		assertEquals(1, result.conflicts)
		// The record already held is the newer description of that folder and stays untouched.
		assertEquals(1L, journal.read().first { it.path.endsWith("Held") }.hiddenAt)
		assertEquals(
			listOf("/storage/emulated/0/Held", "/storage/emulated/0/Fresh"),
			journal.read().map { it.path },
		)
	}

	@Test
	fun `an identical record counts as a duplicate rather than a conflict`() = runBlocking {
		val journal = Journal(File(temp.root, "journal.json"))
		journal.addNew(entry("/storage/emulated/0/Same"))

		val result = journal.merge(listOf(entry("/storage/emulated/0/Same")))

		assertEquals(0, result.added)
		assertEquals(1, result.duplicates)
		assertEquals(0, result.conflicts)
		assertEquals(1, journal.read().size)
	}
}
