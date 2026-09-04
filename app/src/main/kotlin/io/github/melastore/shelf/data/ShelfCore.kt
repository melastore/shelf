package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.StoragePaths
import java.io.File

/**
 * The folder machinery, owned by the process rather than by a screen.
 *
 * Emergency hide has to work with no private space on screen and no view model to ask; a broadcast
 * receiver started by the tap can be the only thing running. Keeping the hiding in the view model
 * would make the action a no-op in exactly the case it exists for.
 */
object ShelfCore {

	private lateinit var appContext: Context

	val paths: StoragePaths by lazy { StoragePaths.forCurrentUser() }
	val journal: Journal by lazy { Journal(File(appContext.filesDir, "journal.json")) }
	val registry: FolderRegistry by lazy { FolderRegistry(File(appContext.filesDir, "folders.json")) }
	val hider: FolderHider by lazy { FolderHider(appContext, journal, paths) }
	val preferences: AppPreferences by lazy { AppPreferences(appContext) }
	val decoyVault: DecoyVault by lazy { DecoyVault(File(appContext.filesDir, "decoy_vault.json")) }

	fun install(context: Context) {
		if (!::appContext.isInitialized) appContext = context.applicationContext
	}

	/** Folders on the list that are visible right now, in the order they were added. */
	suspend fun exposedFolders(): List<TrackedFolder> {
		val entries = journal.read().associateBy { paths.toEmulated(it.path) }
		return registry.read().filter { folder ->
			val entry = entries[folder.path]
			if (entry != null) {
				runCatching { hider.isExposed(entry) }.getOrDefault(false)
			} else {
				File(folder.path).exists() || SafGrants.folder(appContext, paths, folder.path) != null
			}
		}
	}

	/**
	 * Hides every visible folder on the list and reports how far it got. Anything needing a grant
	 * Shelf does not hold is skipped; there is nobody to ask, and stopping at the first one would
	 * leave the rest exposed.
	 */
	suspend fun hideAllExposed(preference: HidingPreference): Int {
		var hidden = 0
		val entries = runCatching { journal.read().associateBy { paths.toEmulated(it.path) } }
			.getOrDefault(emptyMap())
		for (folder in runCatching { exposedFolders() }.getOrDefault(emptyList())) {
			val result = runCatching {
				hider.rehide(
					FolderTarget(folder.path, folder.displayName, null),
					preference,
					entries[folder.path],
				)
			}.getOrNull()
			if (result is HideResult.Ok) hidden++
		}
		return hidden
	}
}
