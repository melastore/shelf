package io.github.melastore.shelf.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.melastore.shelf.R
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

	fun show(context: Context, label: String, smallIcon: Int) {
		manager(context).notify(ID, notification(context, label, smallIcon, working = false))
	}

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
				if (!working) addAction(0, context.getString(R.string.emergency_hide), action)
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

	internal fun serviceIntent(context: Context, identity: QuickLockIdentity) =
		Intent(context, QuickLockService::class.java).withIdentity(identity)

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
		// receiver fallback and let the action have its full broadcast window.
		val finish = goAsync()
		val credentialLease = retainRehideCredential(context)
		CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
			try {
				val remaining = performQuickHide(context)
				QuickLockNotification.finish(context, identity, remaining)
				QuickLockSignal.completed()
			} finally {
				credentialLease.close()
				finish.finish()
			}
		}
	}
}

/** A long hide cannot live inside a BroadcastReceiver's short execution window. */
class QuickLockService : Service() {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var operation: Job? = null
	private var credentialLease: AutoCloseable? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		ShelfCore.install(this)
		val identity = intent?.let { QuickLockNotification.identity(this, it) }
			?: QuickLockNotification.identity(this)
		// Recent releases refuse a foreground start from states this can legitimately be reached from.
		// The hide matters more than the foreground guarantee, so a refusal leaves the work running
		// in an ordinary service rather than taking the app down with it.
		val foreground = runCatching {
			startForeground(QuickLockNotification.ID, QuickLockNotification.showWorking(this, identity))
		}.isSuccess
		if (operation?.isActive == true) return START_NOT_STICKY

		credentialLease = retainRehideCredential(this)
		operation = scope.launch {
			val remaining = try {
				performQuickHide(this@QuickLockService)
			} finally {
				credentialLease?.close()
				credentialLease = null
			}
			QuickLockNotification.finish(this@QuickLockService, identity, remaining)
			QuickLockSignal.completed()
			if (foreground) {
				stopForeground(if (remaining == 0) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
			}
			// Not stopSelf(startId). A second tap arriving while this ran left a newer id behind, and
			// stopping against the older one does nothing, leaving a foreground service and its
			// notification up for good.
			stopSelf()
		}
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		credentialLease?.close()
		credentialLease = null
		scope.cancel()
		super.onDestroy()
	}

	companion object {
		internal fun start(context: Context, identity: QuickLockIdentity): Boolean = runCatching {
			ContextCompat.startForegroundService(context, QuickLockNotification.serviceIntent(context, identity))
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

private suspend fun performQuickHide(context: Context): Int {
	ShelfCore.install(context)
	runCatching { ShelfCore.decoyVault.setAllHidden(true) }
	ShelfCore.hideAllExposed(ShelfCore.preferences.hidingPreference())
	val remaining = runCatching { ShelfCore.exposedFolders().size }.getOrDefault(1)
	if (remaining == 0) EmergencyCredentialStore.clear(context)
	return remaining
}
