package io.github.melastore.shelf.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.melastore.shelf.data.ShelfCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Puts the emergency-hide notification back after a restart.
 *
 * The notification tracks folders left in the open, which survive a reboot in every hiding method —
 * but the notification does not, and nothing reposts it until the app is opened again. That leaves
 * the one state the button exists for with no button: folders exposed, and the only way to put them
 * back is to find the app, get past the disguise, and enter a credential.
 *
 * It reads a count and nothing else. No path, name, or record is touched, and nothing is posted
 * unless the owner asked for the notification and something is actually exposed.
 */
class BootReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
		ShelfCore.install(context)
		val appContext = context.applicationContext
		val finish = goAsync()
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			try {
				if (!ShelfCore.preferences.quickLockNotification()) return@launch
				val exposed = runCatching { ShelfCore.exposedFolders().size }.getOrDefault(0)
				if (exposed == 0) return@launch
				val identity = QuickLockNotification.identity(appContext)
				QuickLockNotification.show(appContext, identity.label, identity.icon)
			} finally {
				finish.finish()
			}
		}
	}
}
