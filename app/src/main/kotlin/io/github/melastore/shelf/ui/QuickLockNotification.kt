package io.github.melastore.shelf.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.security.EmergencyCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The emergency-hide signal, delivered straight to whatever is listening in this process.
 *
 * The action used to leave a flag for the next `onResume`, which never came: pulling down the shade
 * does not stop the activity underneath, so collapsing it resumes nothing and the private space
 * stayed on screen. The one moment the button existed for was the one moment it did nothing.
 */
object QuickLockSignal {

	private val _requests = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
		extraBufferCapacity = 1,
	)
	private val _completions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

	/** Replayed, so a signal arriving while the app is still starting is not lost. */
	val requests = _requests.asSharedFlow()
	val completions = _completions.asSharedFlow()

	fun request() {
		_requests.tryEmit(Unit)
	}

	fun completed() {
		_completions.tryEmit(Unit)
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	fun consume() {
		_requests.resetReplayCache()
	}
}

/**
 * A quiet notification carrying the emergency-hide action.
 *
 * Posted only while a folder is sitting in the open, which outlasts the private space being on
 * screen and is the one state worth a one-tap way out. With everything hidden the action has nothing
 * to do, so there is no notification to explain. Nothing on it names the private space, the channel
 * name included: that stays listed in the app's notification settings long after the notification.
 */
object QuickLockNotification {
	private const val CHANNEL = "background_activity"
	internal const val ID = 704
	private const val EXTRA_LABEL = "label"
	private const val EXTRA_ICON = "icon"

	internal const val ACTION_WATCH = "io.github.melastore.shelf.action.WATCH"
	internal const val ACTION_HIDE = "io.github.melastore.shelf.action.HIDE"

	fun show(context: Context, label: String, smallIcon: Int) {
		manager(context).notify(ID, notification(context, label, smallIcon, working = false))
	}

	internal fun idle(context: Context, identity: QuickLockIdentity) =
		notification(context, identity.label, identity.icon, working = false)

	internal fun showWorking(context: Context, identity: QuickLockIdentity) =
		notification(context, identity.label, identity.icon, working = true)

	internal fun finish(context: Context, identity: QuickLockIdentity, remaining: Int) {
		if (remaining == 0) {
			cancel(context)
		} else {
			manager(context).notify(
				ID,
				notification(context, identity.label, identity.icon, working = false, retry = true),
			)
		}
	}

	private fun notification(
		context: Context,
		label: String,
		smallIcon: Int,
		working: Boolean,
		retry: Boolean = false,
	): android.app.Notification {
		ensureChannel(context)
		val action = PendingIntent.getBroadcast(
			context,
			0,
			hideIntent(context, QuickLockIdentity(label, smallIcon)),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		return NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(smallIcon)
			.setContentTitle(label)
			.setContentText(
				context.getString(
					when {
						retry -> R.string.quick_lock_notification_retry
						working -> R.string.quick_lock_notification_working
						else -> R.string.quick_lock_notification_text
					},
				),
			)
			.setSilent(true)
			.setShowWhen(false)
			.setVisibility(NotificationCompat.VISIBILITY_SECRET)
			.setOngoing(working)
			.apply {
				if (!working) {
					setContentIntent(action)
					addAction(0, context.getString(R.string.emergency_hide), action)
				}
			}
			.build()
	}

	private fun ensureChannel(context: Context) {
		manager(context).createNotificationChannel(
			NotificationChannel(
				CHANNEL,
				context.getString(R.string.notification_channel_name),
				NotificationManager.IMPORTANCE_LOW,
			),
		)
	}

	/**
	 * Starts the same process-independent hide the notification action uses. An explicit broadcast on
	 * purpose: automatic hiding has to survive the activity and its view model being destroyed the
	 * moment Shelf leaves the foreground.
	 */
	fun requestHide(context: Context) {
		context.sendBroadcast(hideIntent(context, identity(context)))
	}

	/**
	 * Puts the hide machinery in the foreground for as long as a folder is exposed.
	 *
	 * Screen-off is a registered-only broadcast, so no manifest receiver can catch it and the
	 * listener has to be one this process holds. Tying that to the activity meant automatic hiding
	 * stopped working the moment Android reclaimed the process, which is most of the time the setting
	 * is meant to cover. Being foreground already also settles the other half: recent releases refuse
	 * a foreground start from a receiver, which pushed the whole hide into a broadcast's window.
	 *
	 * Call it from the foreground. Returns false if the start was refused, so the caller can fall
	 * back to the plain notification.
	 */
	internal fun startWatch(context: Context, identity: QuickLockIdentity): Boolean = runCatching {
		ContextCompat.startForegroundService(context, serviceIntent(context, identity, ACTION_WATCH))
		true
	}.getOrDefault(false)

	fun stopWatch(context: Context) {
		runCatching { context.stopService(Intent(context, QuickLockService::class.java)) }
		cancel(context)
	}

	internal fun identity(context: Context): QuickLockIdentity {
		val (label, icon) = ShelfCore.preferences.decoy().identity()
		return QuickLockIdentity(context.getString(label), icon)
	}

	internal fun identity(context: Context, intent: Intent): QuickLockIdentity {
		val fallback = identity(context)
		return QuickLockIdentity(
			intent.getStringExtra(EXTRA_LABEL) ?: fallback.label,
			intent.getIntExtra(EXTRA_ICON, fallback.icon),
		)
	}

	private fun hideIntent(context: Context, identity: QuickLockIdentity) =
		Intent(context, QuickLockReceiver::class.java).withIdentity(identity)

	internal fun serviceIntent(context: Context, identity: QuickLockIdentity, action: String) =
		Intent(context, QuickLockService::class.java).setAction(action).withIdentity(identity)

	private fun Intent.withIdentity(identity: QuickLockIdentity) = putExtra(EXTRA_LABEL, identity.label)
		.putExtra(EXTRA_ICON, identity.icon)

	fun cancel(context: Context) = manager(context).cancel(ID)

	private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

internal data class QuickLockIdentity(val label: String, val icon: Int)

/**
 * Does the hiding itself rather than asking the app to.
 *
 * The tap that gets here may be the only thing running. The notification outlives the private space,
 * so by the time it is used the activity is usually gone and its view model with it, and signalling
 * in the hope that something is listening would only work while the app was already open.
 */
class QuickLockReceiver : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		ShelfCore.install(context)
		val identity = QuickLockNotification.identity(context, intent)
		// Closing the private UI must not wait on service scheduling. Any exposed folder already has a
		// device-bound re-hide credential, armed by the unhide that exposed it.
		QuickLockSignal.request()
		if (QuickLockService.start(context, identity)) return

		// Some OEM builds refuse a foreground start for an automatic background transition, so keep a
		// receiver fallback and let the action have its full broadcast window. It is the short path:
		// a folder of any size can outlast it, which is why the watch service exists.
		val finish = goAsync()
		val credentialLease = retainRehideCredential(context)
		val wakeLock = holdCpu(context)
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			try {
				val remaining = performQuickHide(context)
				QuickLockNotification.finish(context, identity, remaining)
				QuickLockSignal.completed()
			} finally {
				credentialLease.close()
				wakeLock.close()
				finish.finish()
			}
		}
	}
}

/**
 * Holds the hide machinery while a folder is exposed, and runs the hide itself.
 *
 * Both halves need the same foreground guarantee: the screen-off listener has to outlive the
 * activity, and the hide is far longer than a broadcast window (header protection and clearing
 * MediaStore rows run into tens of seconds on a folder of any size).
 */
class QuickLockService : Service() {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var operation: Job? = null
	private var credentialLease: AutoCloseable? = null
	private var wakeLock: AutoCloseable? = null
	private var identity: QuickLockIdentity? = null
	private var watching = false

	private val screenReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action != Intent.ACTION_SCREEN_OFF) return
			if (ShelfCore.preferences.autoHideMode() == AutoHideMode.NEVER) return
			QuickLockSignal.request()
			hideNow(identity ?: QuickLockNotification.identity(context))
		}
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		ShelfCore.install(this)
		val identity = intent?.let { QuickLockNotification.identity(this, it) }
			?: QuickLockNotification.identity(this)
		this.identity = identity
		// A restart after a low-memory kill arrives with a null intent and no action; treat it as the
		// watch it was, not as a hide nobody asked for.
		val hide = intent != null && intent.action != QuickLockNotification.ACTION_WATCH
		// Recent releases refuse a foreground start from states this can legitimately be reached from.
		// The hide matters more than the foreground guarantee, so a refusal leaves the work running in
		// an ordinary service rather than taking the app down with it.
		runCatching {
			startForeground(
				QuickLockNotification.ID,
				if (hide) {
					QuickLockNotification.showWorking(this, identity)
				} else {
					QuickLockNotification.idle(this, identity)
				},
			)
		}
		startWatching()
		if (hide) hideNow(identity)
		// Sticky: the watch is the point of the service, and a restart puts it back without waiting
		// for the app to be opened again.
		return START_STICKY
	}

	private fun startWatching() {
		if (watching) return
		ContextCompat.registerReceiver(
			this,
			screenReceiver,
			IntentFilter(Intent.ACTION_SCREEN_OFF),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		watching = true
	}

	// The wake lock is not optional here: this runs with the screen off by design, and a suspend part
	// way through leaves a journalled folder still readable.
	private fun hideNow(identity: QuickLockIdentity) {
		if (operation?.isActive == true) return
		runCatching {
			startForeground(QuickLockNotification.ID, QuickLockNotification.showWorking(this, identity))
		}
		credentialLease = retainRehideCredential(this)
		wakeLock = holdCpu(this)
		operation = scope.launch {
			val remaining = try {
				performQuickHide(this@QuickLockService)
			} finally {
				credentialLease?.close()
				credentialLease = null
				wakeLock?.close()
				wakeLock = null
			}
			QuickLockSignal.completed()
			QuickLockNotification.finish(this@QuickLockService, identity, remaining)
			if (remaining == 0) {
				stopForeground(STOP_FOREGROUND_REMOVE)
				// Not stopSelf(startId). A second request arriving while this ran left a newer id
				// behind, and stopping against the older one does nothing, leaving a foreground
				// service and its notification up for good.
				stopSelf()
			}
			// Anything left in the open keeps the watch up, with the notification back on the retry.
		}
	}

	override fun onDestroy() {
		if (watching) runCatching { unregisterReceiver(screenReceiver) }
		watching = false
		credentialLease?.close()
		credentialLease = null
		wakeLock?.close()
		wakeLock = null
		scope.cancel()
		super.onDestroy()
	}

	companion object {
		internal fun start(context: Context, identity: QuickLockIdentity): Boolean = runCatching {
			ContextCompat.startForegroundService(
				context,
				QuickLockNotification.serviceIntent(context, identity, QuickLockNotification.ACTION_HIDE),
			)
			true
		}.getOrDefault(false)
	}
}

private fun retainRehideCredential(context: Context): AutoCloseable {
	val persisted = if (ContentCredential.isAvailable()) null else EmergencyCredentialStore.load(context)
	if (persisted != null) ContentCredential.set(persisted)
	val lease = ContentCredential.retain()
	persisted?.fill(' ')
	ContentCredential.clear()
	return lease
}

/** Tagged after the disguise's channel, so a battery screen listing wake locks says no more than the notification. */
private fun holdCpu(context: Context): AutoCloseable {
	val manager = context.getSystemService(PowerManager::class.java) ?: return AutoCloseable {}
	val lock = runCatching {
		manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shelf:background_activity").apply {
			setReferenceCounted(false)
			acquire(WAKE_LOCK_TIMEOUT_MILLIS)
		}
	}.getOrNull() ?: return AutoCloseable {}
	return AutoCloseable { runCatching { if (lock.isHeld) lock.release() } }
}

private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L

private suspend fun performQuickHide(context: Context): Int {
	ShelfCore.install(context)
	runCatching { ShelfCore.decoyVault.setAllHidden(true) }
	ShelfCore.hideAllExposed(ShelfCore.preferences.hidingPreference())
	val remaining = runCatching { ShelfCore.exposedFolders().size }.getOrDefault(1)
	if (remaining == 0) EmergencyCredentialStore.clear(context)
	return remaining
}
