package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicJsonListRecoveryTest {

	@get:Rule val temp = TemporaryFolder()

	private fun list(file: File) = AtomicJsonList(file, ListSerializer(String.serializer()))

	private fun corruptCopies() = temp.root.listFiles().orEmpty().filter { ".corrupt-" in it.name }

	@Test
	fun `a damaged file covered by its backup is not copied on every read`() = runBlocking {
		val file = File(temp.root, "records.json")
		val records = list(file)
		records.update { listOf("first") }
		records.update { listOf("first", "second") }
		file.writeText("{ this is not json")

		repeat(5) { assertEquals(listOf("first"), records.read()) }

		assertTrue("reads should not accumulate copies", corruptCopies().isEmpty())
	}

	@Test
	fun `a damaged file with no usable backup is preserved once and reported`() = runBlocking {
		val file = File(temp.root, "records.json")
		val records = list(file)
		records.update { listOf("only") }
		file.writeText("{ this is not json")

		repeat(3) { assertThrows(RecordsCorrupted::class.java) { runBlocking { records.read() } } }

		assertEquals(1, corruptCopies().size)
		assertEquals("{ this is not json", corruptCopies().single().readText())
	}
}
