package io.github.melastore.shelf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.melastore.shelf.R

/**
 * What the entry gesture shows instead of the unlock screen, for an owner who would rather be seen
 * failing to open an app than opening a locked one.
 *
 * Laid out to match the dialog this Android version actually puts up: a left-aligned title, then
 * rows with leading icons rather than a row of buttons, and "Show details" expanding into a stack
 * trace. Every row does what it says, so someone who takes the phone and works through them finds
 * an ordinary crash and no hint that anything is behind it. The way through is a long press on the
 * title, which leaves nothing on screen to notice.
 *
 * It is only reachable after the entry gesture, so nobody sees it who was not already being shown
 * something by the owner.
 */
@Composable
fun FakeCrashDialog(appName: String, onUnlock: () -> Unit, onAppInfo: () -> Unit, onClose: () -> Unit,) {
	var details by remember { mutableStateOf(false) }

	Dialog(
		// Back dismisses a real one by closing the app, and tapping outside does nothing.
		onDismissRequest = onClose,
		properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
	) {
		Surface(
			modifier = Modifier.fillMaxWidth(0.88f),
			shape = RoundedCornerShape(28.dp),
			// The system dialog sits close to the background, not raised off it.
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
		) {
			Column(Modifier.padding(vertical = 20.dp)) {
				val interaction = remember { MutableInteractionSource() }
				Text(
					text = stringResource(R.string.fake_crash_message, appName),
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(horizontal = 24.dp).combinedClickable(
						interactionSource = interaction,
						// No ripple: a title that lights up under a finger is a title worth pressing.
						indication = null,
						onClick = {},
						onLongClick = onUnlock,
					),
				)
				Spacer(Modifier.height(12.dp))
				CrashRow(stringResource(R.string.fake_crash_app_info), onAppInfo) { InfoGlyph(it) }
				CrashRow(stringResource(R.string.fake_crash_close), onClose) { tint ->
					Icon(Icons.Filled.Close, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
				}
				CrashRow(stringResource(R.string.fake_crash_details), { details = !details }) { InfoGlyph(it) }
				if (details) {
					Text(
						text = stringResource(R.string.fake_crash_trace, appName),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Start,
						modifier = Modifier.padding(horizontal = 24.dp)
							.heightIn(max = 200.dp)
							.verticalScroll(rememberScrollState()),
					)
				}
			}
		}
	}
}

@Composable
private fun CrashRow(label: String, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
	Row(
		Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		icon(MaterialTheme.colorScheme.primary)
		Spacer(Modifier.width(24.dp))
		Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
	}
}

/** Drawn rather than imported: the outlined set is a separate artifact, for one glyph. */
@Composable
private fun InfoGlyph(tint: Color) {
	Canvas(Modifier.size(24.dp)) {
		val stroke = size.minDimension * 0.085f
		drawCircle(tint, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
		drawCircle(tint, radius = stroke * 0.62f, center = Offset(size.width / 2f, size.height * 0.30f))
		drawLine(
			tint,
			Offset(size.width / 2f, size.height * 0.44f),
			Offset(size.width / 2f, size.height * 0.73f),
			stroke,
			StrokeCap.Round,
		)
	}
}
