package io.github.melastore.shelf.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.melastore.shelf.data.DecoyType

/** Keeps exactly one launcher entry enabled, without restarting the running activity. */
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

	/** The share entry wears the same name and icon as the launcher one, or is gone altogether. */
	fun applyShare(decoy: DecoyType, enabled: Boolean) {
		val selected = shareAliases.getValue(decoy).takeIf { enabled }
		shareAliases.values.forEach {
			val state = if (it == selected) {
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED
			} else {
				PackageManager.COMPONENT_ENABLED_STATE_DISABLED
			}
			set(it, state)
		}
	}

	private fun set(className: String, state: Int) {
		packageManager.setComponentEnabledSetting(
			ComponentName(appContext, className),
			state,
			PackageManager.DONT_KILL_APP,
		)
	}

	companion object {
		fun shareComponents(context: Context): List<ComponentName> =
			shareAliases.values.map { ComponentName(context.packageName, it) }

		private const val PACKAGE = "io.github.melastore.shelf"
		private val aliases = mapOf(
			DecoyType.NONE to "$PACKAGE.ShelfLauncher",
			DecoyType.HABITS to "$PACKAGE.HabitLauncher",
			DecoyType.CALENDAR to "$PACKAGE.CalendarLauncher",
			DecoyType.CALCULATOR to "$PACKAGE.CalculatorLauncher",
		)
		private val shareAliases = mapOf(
			DecoyType.NONE to "$PACKAGE.ShareToShelf",
			DecoyType.HABITS to "$PACKAGE.ShareToHabits",
			DecoyType.CALENDAR to "$PACKAGE.ShareToCalendar",
			DecoyType.CALCULATOR to "$PACKAGE.ShareToCalculator",
		)
	}
}
