package io.github.melastore.shelf.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.github.melastore.shelf.data.ShelfCore

/**
 * Puts exposed folders back after a set time, whatever the screen is doing. Screen-off auto hide never
 * fires on a phone left awake on a charger or playing a video.
 *
 * The alarm goes to the receiver the notification action uses, so it works with the app gone.
 * Exact, because an inexact one may land up to three quarters of the interval late. An exact alarm
 * also lets the receiver start the hide service, where an inexact one leaves it the broadcast window.
 */
internal object RehideTimer {

	fun sync(context: Context, exposed: Boolean) {
		val preferences = ShelfCore.preferences
		val alarms = context.getSystemService(AlarmManager::class.java) ?: return
		val minutes = preferences.rehideMinutes()
		if (!exposed || minutes <= 0) {
			alarms.cancel(alarm(context))
			preferences.setRehideAt(0L)
			return
		}
		val now = SystemClock.elapsedRealtime()
		val window = minutes * 60_000L
		val at = preferences.rehideAt()
		// Once per exposure, so unhiding a second folder does not push the first one's time back.
		// Anything further off than the window is left over from before a reboot.
		if (at > now && at - now <= window) return
		val next = now + window
		preferences.setRehideAt(next)
		// Some builds still refuse the permission, so late beats never.
		val exact = runCatching {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) return@runCatching false
			alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, alarm(context))
			true
		}.getOrDefault(false)
		if (!exact) alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, alarm(context))
	}

	/** Alarms do not survive a reboot, so whatever was recorded is dropped and set again. */
	fun reschedule(context: Context, exposed: Boolean) {
		ShelfCore.preferences.setRehideAt(0L)
		sync(context, exposed)
	}

	private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(
		context,
		REQUEST_CODE,
		QuickLockNotification.hideBroadcast(context),
		PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
	)

	private const val REQUEST_CODE = 41
}
