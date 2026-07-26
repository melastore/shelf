package io.github.melastore.shelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafPathsTest {

	private val root = "/storage/emulated/0"

	@Test fun buildsDocumentIdsRelativeToTheVolume() {
		assertEquals("primary:DCIM/Holiday", SafPaths.documentId(root, "/storage/emulated/0/DCIM/Holiday"))
		assertEquals("primary:", SafPaths.documentId(root, "/storage/emulated/0"))
	}

	@Test fun trailingSlashDoesNotChangeTheId() {
		assertEquals("primary:DCIM", SafPaths.documentId(root, "/storage/emulated/0/DCIM/"))
	}

	@Test fun refusesPathsOnAnotherVolumeOrUser() {
		assertNull(SafPaths.documentId(root, "/storage/emulated/10/DCIM"))
		assertNull(SafPaths.documentId(root, "/data/media/0/DCIM"))
	}

	@Test fun hidingAddsOneDotAndOnlyOne() {
		assertEquals(".Holiday", SafPaths.hiddenName("Holiday"))
		assertEquals(".Holiday", SafPaths.hiddenName(".Holiday"))
	}

	@Test fun renamesInPlaceWithoutMovingTheFolder() {
		assertEquals(
			"/storage/emulated/0/DCIM/.Holiday",
			SafPaths.sibling("/storage/emulated/0/DCIM/Holiday", ".Holiday"),
		)
	}

	@Test fun splitsPathsIntoNameAndParent() {
		assertEquals("Holiday", SafPaths.nameOf("/storage/emulated/0/DCIM/Holiday/"))
		assertEquals("/storage/emulated/0/DCIM", SafPaths.parentOf("/storage/emulated/0/DCIM/Holiday"))
	}
}
