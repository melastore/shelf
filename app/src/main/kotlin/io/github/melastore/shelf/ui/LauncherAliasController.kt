package io.github.melastore.shelf.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.melastore.shelf.data.DecoyType

/** Keeps exactly one launcher identity enabled without restarting the running activity. */
class LauncherAliasController(context: Context) {

	private val appContext = context.applicationContext
	private val packageManager = appContext.packageManager

	fun apply(decoy: DecoyType) {
		val selected = aliases.getValue(decoy)
		set(selected, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
		aliases.values.filterNot { it == selected }.forEach {
			set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
		}
	}

	private fun set(className: String, state: Int) {
		packageManager.setComponentEnabledSetting(
			ComponentName(appContext, className),
			state,
			PackageManager.DONT_KILL_APP,
		)
	}

	private companion object {
		const val PACKAGE = "io.github.melastore.shelf"
		val aliases = mapOf(
			DecoyType.HABITS to "$PACKAGE.HabitLauncher",
			DecoyType.CALENDAR to "$PACKAGE.CalendarLauncher",
			DecoyType.CALCULATOR to "$PACKAGE.CalculatorLauncher",
		)
	}
}
