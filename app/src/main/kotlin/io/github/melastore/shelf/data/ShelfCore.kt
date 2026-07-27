package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.StoragePaths
import java.io.File

/**
 * The folder machinery, owned by the process rather than by a screen.
 *
 * The emergency-hide action has to work when there is no private space on screen and no view model
 * alive to ask — a broadcast receiver can be the only thing running, started by the tap itself. If
 * hiding lived solely in the view model, the action would do nothing in exactly the situation it
 * exists for: the app closed, the folders still sitting in the open.
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

	/** Folders on the list that are not hidden at this moment, in the order they were added. */
	suspend fun exposedFolders(): List<TrackedFolder> {
		val entries = journal.read().associateBy { paths.toEmulated(it.path) }
		return registry.read().filter { folder ->
			val entry = entries[folder.path] ?: return@filter true
			runCatching { hider.isExposed(entry) }.getOrDefault(false)
		}
	}

	/**
	 * Hides everything on the list that is currently visible, reporting only how far it got. Anything
	 * needing a grant Shelf does not hold is skipped: there is no one to ask, and the alternative is
	 * stopping at the first folder and leaving the rest exposed.
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
