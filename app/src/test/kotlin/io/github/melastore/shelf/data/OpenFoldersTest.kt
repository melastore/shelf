package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenFoldersTest {

	@get:Rule val temp = TemporaryFolder()

	@After
	fun tearDown() {
		OpenFolders.ledger = null
	}

	@Test
	fun `a folder a killed process left open is closed by the next one`() = runBlocking {
		OpenFolders.ledger = File(temp.root, "open-folders")
		OpenFolders.open("/data/media/0/Left") {}
		OpenFolders.open("/data/media/0/Closed") {}
		OpenFolders.close("/data/media/0/Closed") { true }
		// What a fresh process sees: the ledger, and nothing open in memory.
		OpenFolders.close("/data/media/0/Left") { false }

		val resealed = mutableListOf<String>()
		OpenFolders.reseal { path ->
			resealed += path
			true
		}

		assertEquals(listOf("/data/media/0/Left"), resealed)
		assertFalse(OpenFolders.ledger!!.exists())
	}

	@Test
	fun `a folder still open in this process is left alone`() = runBlocking {
		OpenFolders.ledger = File(temp.root, "open-folders")
		OpenFolders.open("/data/media/0/Viewing") {}
		try {
			val resealed = mutableListOf<String>()
			OpenFolders.reseal { path ->
				resealed += path
				true
			}

			assertEquals(emptyList<String>(), resealed)
		} finally {
			OpenFolders.close("/data/media/0/Viewing") { true }
		}
	}
}
