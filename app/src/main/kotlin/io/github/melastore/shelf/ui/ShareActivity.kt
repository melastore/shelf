package io.github.melastore.shelf.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.data.UriPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes a share from the gallery or a file manager and hands it to the main screen. Reached only
 * through the share aliases, which follow the disguise.
 *
 * The paths are read here because the share's read grant belongs to this activity and goes with it.
 * Nothing is moved until the space is opened and the owner has named the folder.
 */
class ShareActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) return finish()
		ShelfCore.install(application)
		val uris = sharedUris(intent)
		lifecycleScope.launch {
			if (uris.isNotEmpty()) {
				// Too many is refused whole. Resolving only the first few hundred dropped the rest
				// quietly whenever one of those failed and brought the count under the limit.
				val found = if (uris.size > MAX_PICKED_FILES) {
					emptyList()
				} else {
					val resolver = UriPaths(applicationContext, ShelfCore.paths)
					withContext(Dispatchers.IO) {
						uris.mapNotNull { runCatching { resolver.filePath(it) }.getOrNull() }
					}
				}
				IncomingShare.put(
					IncomingShare.Batch(
						found,
						uris.size - found.size,
						SystemClock.elapsedRealtime(),
						tooMany = uris.size > MAX_PICKED_FILES,
					),
				)
			}
			// The launcher's own intent, so an open task comes forward as it is instead of a second copy
			// of the app starting in the gallery's task.
			packageManager.getLaunchIntentForPackage(packageName)?.let {
				it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
				runCatching { startActivity(it) }
			}
			finish()
		}
	}

	private fun sharedUris(intent: Intent?): List<Uri> {
		intent ?: return emptyList()
		val streams = when (intent.action) {
			Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

			Intent.ACTION_SEND_MULTIPLE ->
				IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

			else -> emptyList()
		}
		val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
		return (streams + clip).distinct()
	}
}
