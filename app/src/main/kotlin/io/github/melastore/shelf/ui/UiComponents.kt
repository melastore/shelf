package io.github.melastore.shelf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.security.CredentialRules
import kotlin.math.roundToInt

/**
 * A keypad rather than a text field: no soft keyboard animates in, nothing lands in the clipboard or
 * a keyboard's learned-word store, and the whole prompt fits above the thumb.
 */
@Composable
fun PinPrompt(
	title: String,
	subtitle: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var entered by remember { mutableStateOf(charArrayOf()) }
	var firstPass by remember { mutableStateOf<CharArray?>(null) }
	var mismatch by remember { mutableStateOf(false) }
	val shake = remember { Animatable(0f) }
	val haptics = LocalHapticFeedback.current
	val confirming = confirmEntry && firstPass != null

	fun replaceEntered(next: CharArray) {
		entered.fill(' ')
		entered = next
	}

	DisposableEffect(Unit) {
		onDispose {
			entered.fill(' ')
			firstPass?.fill(' ')
		}
	}

	LaunchedEffect(mismatch) {
		if (!mismatch) return@LaunchedEffect
		haptics.performHapticFeedback(HapticFeedbackType.LongPress)
		shake.animateTo(
			0f,
			keyframes {
				durationMillis = 300
				(-1f) at 60
				1f at 120
				(-0.6f) at 180
				0.6f at 240
			},
		)
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			securePolicy = SecureFlagPolicy.SecureOn,
		),
	) {
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background,
		) {
			Column(
				Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text(
					if (confirming) stringResource(R.string.confirm_pin) else title,
					style = MaterialTheme.typography.titleLarge,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(4.dp))
				Text(
					if (mismatch) stringResource(R.string.pin_mismatch) else subtitle,
					style = MaterialTheme.typography.bodyMedium,
					color = if (mismatch) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
					textAlign = TextAlign.Center,
				)

				Spacer(Modifier.height(28.dp))
				PinDots(
					entered.size,
					Modifier.offset { IntOffset((shake.value * 10.dp.toPx()).roundToInt(), 0) },
				)
				Spacer(Modifier.height(28.dp))

				Keypad(
					onDigit = {
						if (entered.size < CredentialRules.MAX_PIN) {
							mismatch = false
							replaceEntered(entered + it)
							haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
						}
					},
					onBackspace = {
						if (entered.isNotEmpty()) replaceEntered(entered.copyOf(entered.size - 1))
					},
				)

				Spacer(Modifier.height(20.dp))
				Row(
					Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.Center,
				) {
					TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
					Spacer(Modifier.size(8.dp))
					val ready = entered.size >= CredentialRules.MIN_PIN
					TextButton(
						onClick = {
							val stored = firstPass
							when {
								!confirmEntry -> onConfirm(entered.copyOf())

								stored == null -> {
									firstPass = entered.copyOf()
									replaceEntered(charArrayOf())
									mismatch = false
								}

								stored.contentEquals(entered) -> onConfirm(entered.copyOf())

								else -> {
									stored.fill(' ')
									firstPass = null
									replaceEntered(charArrayOf())
									mismatch = true
								}
							}
						},
						enabled = ready,
					) { Text(if (confirmEntry && firstPass == null) stringResource(R.string.continue_action) else confirmLabel) }
				}
			}
		}
	}
}

@Composable
fun PinDots(filled: Int, modifier: Modifier = Modifier) {
	Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
		repeat(CredentialRules.MIN_PIN.coerceAtLeast(filled)) { index ->
			val on = index < filled
			val size by animateDpAsState(if (on) 14.dp else 11.dp, label = "dot")
			Box(
				Modifier.size(size).clip(CircleShape).background(
					if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
				),
			)
		}
	}
}

@Composable
fun Keypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		listOf("123", "456", "789").forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				row.forEach { KeypadKey(it.toString()) { onDigit(it) } }
			}
		}
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			Spacer(Modifier.size(KEY_SIZE))
			KeypadKey("0") { onDigit('0') }
			val delete = stringResource(R.string.delete_digit)
			Box(
				Modifier.size(KEY_SIZE).clip(CircleShape)
					.clickable(onClick = onBackspace)
					.semantics { contentDescription = delete },
				contentAlignment = Alignment.Center,
			) {
				Text(
					"⌫",
					style = MaterialTheme.typography.headlineSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit) {
	Surface(
		modifier = Modifier.size(KEY_SIZE).clip(CircleShape).clickable(onClick = onClick),
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(label, style = MaterialTheme.typography.headlineSmall)
		}
	}
}

/** One short name, asked for in the same way wherever the app asks for one. */
@Composable
fun TextEntryDialog(
	title: String,
	label: String,
	subtitle: String? = null,
	onAdd: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var text by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		shape = MaterialTheme.shapes.extraLarge,
		title = { Text(title) },
		text = {
			Column {
				if (subtitle != null) {
					Text(
						subtitle,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(12.dp))
				}
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					modifier = Modifier.fillMaxWidth(),
					shape = MaterialTheme.shapes.medium,
					label = { Text(label) },
					singleLine = true,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) {
				Text(stringResource(R.string.add))
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@Composable
fun ConfirmDialog(
	title: String,
	body: String,
	confirmLabel: String,
	dangerous: Boolean = false,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		shape = MaterialTheme.shapes.extraLarge,
		title = { Text(title) },
		text = { Text(body) },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text(
					confirmLabel,
					color = if (dangerous) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.primary
					},
				)
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

/**
 * An invisible knock target in the top-right corner.
 *
 * The target is large enough to hit without aiming, and the window between taps is generous — a
 * gesture nobody can find by accident is no use if the owner cannot find it
 * either. A short vibration from the second tap onwards confirms the count is building without
 * showing anything on screen.
 */
@Composable
fun KnockTarget(onTriggered: () -> Unit, modifier: Modifier = Modifier) {
	var taps by remember { mutableIntStateOf(0) }
	var lastTap by remember { mutableLongStateOf(0L) }
	val interaction = remember { MutableInteractionSource() }
	val haptics = LocalHapticFeedback.current
	Box(
		modifier = modifier
			.testTag("secret-knock")
			.size(KNOCK_TARGET)
			.clickable(interactionSource = interaction, indication = null) {
				val now = System.currentTimeMillis()
				taps = if (now - lastTap <= KNOCK_GAP_MILLIS) taps + 1 else 1
				lastTap = now
				when {
					taps >= REQUIRED_KNOCKS -> {
						taps = 0
						haptics.performHapticFeedback(HapticFeedbackType.LongPress)
						onTriggered()
					}

					taps >= 2 -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
				}
			},
	)
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge)
				.background(MaterialTheme.colorScheme.primaryContainer),
		)
		Spacer(Modifier.height(20.dp))
		Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
		Spacer(Modifier.height(6.dp))
		Text(
			body,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}

/** Groups rows into one rounded block, the way current system settings screens read. */
@Composable
fun SettingsGroup(title: String, summary: String? = null, content: @Composable () -> Unit) {
	Column(Modifier.fillMaxWidth()) {
		Text(
			title.uppercase(),
			modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 6.dp),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.primary,
		)
		if (summary != null) {
			Text(
				summary,
				modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Surface(
			modifier = Modifier.fillMaxWidth(),
			shape = MaterialTheme.shapes.large,
			color = MaterialTheme.colorScheme.surfaceContainer,
		) {
			Column(Modifier.padding(vertical = 4.dp), content = { content() })
		}
	}
}

@Composable
fun SettingsRow(
	title: String,
	summary: String,
	enabled: Boolean = true,
	dangerous: Boolean = false,
	trailing: @Composable (() -> Unit)? = null,
	onClick: (() -> Unit)? = null,
) {
	Row(
		modifier = Modifier.fillMaxWidth()
			.let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
			.padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(Modifier.weight(1f)) {
			Text(
				title,
				style = MaterialTheme.typography.titleSmall,
				color = when {
					!enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
					dangerous -> MaterialTheme.colorScheme.error
					else -> MaterialTheme.colorScheme.onSurface
				},
			)
			Spacer(Modifier.height(2.dp))
			Text(
				summary,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (trailing != null) {
			Spacer(Modifier.size(12.dp))
			trailing()
		}
	}
}

/** A filled circle when chosen, a ring when not: readable at a glance without a radio button. */
@Composable
fun SelectionMark(selected: Boolean) {
	val ring = MaterialTheme.colorScheme.outlineVariant
	val on = MaterialTheme.colorScheme.primary
	Box(
		Modifier.size(24.dp).clip(CircleShape)
			.background(if (selected) on else Color.Transparent)
			.border(2.dp, if (selected) on else ring, CircleShape),
		contentAlignment = Alignment.Center,
	) {
		AnimatedVisibility(selected) {
			Box(
				Modifier.size(9.dp).clip(CircleShape)
					.background(MaterialTheme.colorScheme.onPrimary),
			)
		}
	}
}

/** A square, tappable tile used for the disguise chooser. */
@Composable
fun ChoiceTile(
	label: String,
	selected: Boolean,
	modifier: Modifier = Modifier,
	icon: @Composable () -> Unit,
	onClick: () -> Unit,
) {
	val border by animateDpAsState(if (selected) 2.dp else 1.dp, label = "tile")
	Surface(
		modifier = modifier.aspectRatio(1f).clickable(onClick = onClick),
		shape = RoundedCornerShape(24.dp),
		color = if (selected) {
			MaterialTheme.colorScheme.primaryContainer
		} else {
			MaterialTheme.colorScheme.surfaceContainer
		},
		border = androidx.compose.foundation.BorderStroke(
			border,
			if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
		),
	) {
		Column(
			Modifier.fillMaxSize().padding(10.dp),
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			icon()
			Spacer(Modifier.height(8.dp))
			Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
		}
	}
}

private val KEY_SIZE = 68.dp
private val KNOCK_TARGET = 88.dp
private const val REQUIRED_KNOCKS = 5
private const val KNOCK_GAP_MILLIS = 1_200L
