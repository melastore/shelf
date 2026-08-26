package io.github.melastore.shelf.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.melastore.shelf.R

/**
 * What the entry gesture shows instead of the unlock screen, for an owner who would rather be seen
 * failing to open an app than opening a locked one.
 *
 * Both buttons do exactly what they say. "Close app" ends the task and "App info" opens Android's
 * own page for it, so someone who takes the phone and prods at this finds an ordinary crash and no
 * hint that anything is behind it. The way through is a long press on the message, which leaves
 * nothing on screen to notice.
 *
 * It is only reachable after the entry gesture, so nobody sees it who was not already being shown
 * something by the owner.
 */
@Composable
fun FakeCrashDialog(appName: String, onUnlock: () -> Unit, onAppInfo: () -> Unit, onClose: () -> Unit,) {
	AlertDialog(
		// Back dismisses a real one by closing the app, and tapping outside does nothing.
		onDismissRequest = onClose,
		properties = DialogProperties(dismissOnClickOutside = false),
		title = {
			val interaction = remember { MutableInteractionSource() }
			Text(
				text = stringResource(R.string.fake_crash_message, appName),
				modifier = Modifier.combinedClickable(
					interactionSource = interaction,
					// No ripple: a message that lights up under a finger is a message worth pressing.
					indication = null,
					onClick = {},
					onLongClick = onUnlock,
				),
			)
		},
		dismissButton = {
			TextButton(onClick = onAppInfo) { Text(stringResource(R.string.fake_crash_app_info)) }
		},
		confirmButton = {
			TextButton(onClick = onClose) { Text(stringResource(R.string.fake_crash_close)) }
		},
	)
}
