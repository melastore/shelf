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

/**
 * These records are the only way back from a hide or a lock, so the cases that matter here are the
 * damaged ones: what must never happen is a corrupt file reading as "nothing was hidden".
 */
class AtomicJsonListTest {

	@get:Rule val temp = TemporaryFolder()

	private fun store(file: File) = AtomicJsonList(file, ListSerializer(String.serializer()))

	@Test fun missingFileReadsEmpty() = runBlocking {
		assertEquals(emptyList<String>(), store(temp.newFile("absent.json").also { it.delete() }).read())
	}

	@Test fun roundTripsThroughAnUpdate() = runBlocking {
		val file = File(temp.root, "list.json")
		val store = store(file)
		store.update { it + "one" }
		store.update { it + "two" }
		assertEquals(listOf("one", "two"), store.read())
	}

	@Test fun corruptFileWithoutABackupIsReportedNotEmptied() = runBlocking {
		val file = File(temp.root, "list.json")
		file.writeText("{ this is not the list }")

		val failure = assertThrows(RecordsCorrupted::class.java) {
			runBlocking { store(file).read() }
		}
		assertTrue(failure.preserved.isFile)
		assertEquals("{ this is not the list }", failure.preserved.readText())
	}

	@Test fun corruptFileIsNeverOverwrittenByTheNextWrite() = runBlocking {
		val file = File(temp.root, "list.json")
		file.writeText("{ truncated")

		assertThrows(RecordsCorrupted::class.java) {
			runBlocking { store(file).update { it + "new" } }
		}
		assertEquals("{ truncated", file.readText())
	}

	@Test fun corruptFileFallsBackToTheLastGoodGeneration() = runBlocking {
		val file = File(temp.root, "list.json")
		val store = store(file)
		store.update { it + "kept" }
		store.update { it + "also kept" }

		file.writeText("garbage")

		assertEquals(listOf("kept"), store.read())
		assertTrue(File(temp.root, "list.json.bak").isFile)
	}

	@Test fun updateAfterFallbackDoesNotReplaceTheGoodBackupWithCorruption() = runBlocking {
		val file = File(temp.root, "list.json")
		val store = store(file)
		store.update { it + "first" }
		store.update { it + "second" }
		file.writeText("broken")

		store.update { it + "recovered" }

		assertEquals(listOf("first", "recovered"), store.read())
		assertEquals(listOf("first"), store(File(temp.root, "list.json.bak")).read())
	}

	@Test fun corruptBackupWithoutAPrimaryIsReported() = runBlocking {
		val file = File(temp.root, "list.json")
		File(temp.root, "list.json.bak").writeText("broken")

		assertThrows(RecordsCorrupted::class.java) {
			runBlocking { store(file).read() }
		}
		Unit
	}

	@Test fun mutateSeesTheCurrentRecordsAndReportsItsDecision() = runBlocking {
		val file = File(temp.root, "list.json")
		val store = store(file)
		store.update { it + "one" }

		val added = store.mutate { current ->
			if ("one" in current) current to false else (current + "one") to true
		}
		assertEquals(false, added)
		assertEquals(listOf("one"), store.read())
	}
}
