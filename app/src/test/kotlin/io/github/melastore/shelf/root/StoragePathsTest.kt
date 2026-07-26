package io.github.melastore.shelf.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathsTest {

	private val owner = StoragePaths(0)

	@Test fun mapsEmulatedRootToBacking() {
		assertEquals("/data/media/0/DCIM", owner.toBacking("/storage/emulated/0/DCIM"))
	}

	@Test fun mapsSdcardAliasToBacking() {
		assertEquals("/data/media/0/Pictures/Cats", owner.toBacking("/sdcard/Pictures/Cats"))
	}

	@Test fun leavesBackingPathUnchanged() {
		assertEquals("/data/media/0/Docs", owner.toBacking("/data/media/0/Docs"))
	}

	@Test fun trailingSlashIsIgnored() {
		assertEquals("/data/media/0/Docs", owner.toBacking("/sdcard/Docs/"))
	}

	@Test fun rejectsPathOutsidePrimaryStorage() {
		assertThrows(IllegalArgumentException::class.java) {
			owner.toBacking("/data/data/com.example/files")
		}
	}

	@Test fun roundTripsBackToEmulated() {
		val backing = owner.toBacking("/storage/emulated/0/A/B")
		assertEquals("/storage/emulated/0/A/B", owner.toEmulated(backing))
	}

	@Test fun storageRootIsNotASafeTarget() {
		assertFalse(owner.isSafeTarget("/data/media/0"))
		assertFalse(owner.isSafeTarget("/data/media/0/"))
	}

	@Test fun folderUnderRootIsASafeTarget() {
		assertTrue(owner.isSafeTarget("/data/media/0/Secret"))
	}

	// --- Per-user volumes ---

	@Test fun secondaryUserGetsItsOwnVolume() {
		val work = StoragePaths(10)
		assertEquals("/data/media/10/DCIM", work.toBacking("/storage/emulated/10/DCIM"))
		assertEquals("/storage/emulated/10/DCIM", work.toEmulated("/data/media/10/DCIM"))
	}

	@Test fun anotherUsersVolumeIsNotThisUsersStorage() {
		val work = StoragePaths(10)
		assertThrows(IllegalArgumentException::class.java) {
			work.toBacking("/storage/emulated/0/DCIM")
		}
		assertFalse(work.isSafeTarget("/data/media/0/Secret"))
	}

	@Test fun sdcardAliasFollowsTheCurrentUser() {
		assertEquals("/data/media/10/Docs", StoragePaths(10).toBacking("/sdcard/Docs"))
	}

	// --- Traversal ---

	@Test fun rejectsTraversalSegments() {
		assertThrows(IllegalArgumentException::class.java) {
			owner.toBacking("/storage/emulated/0/../../data/data/com.example")
		}
	}

	@Test fun traversalIsNotASafeTargetEvenUnderTheRoot() {
		assertFalse(owner.isSafeTarget("/data/media/0/../../data/data/com.example"))
		assertFalse(owner.isSafeTarget("/data/media/0/./Secret"))
	}

	@Test fun rejectsRelativeAndEmptySegments() {
		assertThrows(IllegalArgumentException::class.java) { owner.toBacking("sdcard/Docs") }
		assertThrows(IllegalArgumentException::class.java) { owner.toBacking("/sdcard//Docs") }
	}
}
